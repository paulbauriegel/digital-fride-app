package com.example.groceriesapp.data.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON-LD shapes here are the ones real recipe sites actually emit — a bare Recipe object, an
 * `@graph` wrapper, a top-level array, HowToStep objects, HowToSection nesting, and a single
 * newline-separated instruction blob. Every one of these appeared while building the importer.
 */
class RecipeJsonLdTest {

    private fun page(jsonLd: String): String = """
        <html><head>
        <script type="application/ld+json">$jsonLd</script>
        </head><body><p>ignored</p></body></html>
    """.trimIndent()

    private val minimalRecipe = """
        {
          "@context": "https://schema.org",
          "@type": "Recipe",
          "name": "Tomato Pasta",
          "recipeYield": "4 Portionen",
          "totalTime": "PT35M",
          "recipeIngredient": ["400 g Spaghetti", "2 Zwiebeln", "Salz"],
          "recipeInstructions": [
            {"@type": "HowToStep", "text": "Nudeln kochen."},
            {"@type": "HowToStep", "text": "Zwiebeln anbraten."}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a bare recipe object`() {
        val recipe = RecipeJsonLd.parseFromHtml(page(minimalRecipe), createdAtEpochMillis = 1L)
        assertNotNull(recipe)
        requireNotNull(recipe)
        assertEquals("Tomato Pasta", recipe.title)
        assertEquals(4, recipe.servings)
        assertEquals(35, recipe.prepMinutes)
        assertEquals(listOf("Nudeln kochen.", "Zwiebeln anbraten."), recipe.steps)
        assertEquals(RecipeSource.USER, recipe.source)
        assertTrue(recipe.id.startsWith("user_"))
    }

    @Test
    fun `parses amounts and units out of ingredient lines`() {
        val recipe = requireNotNull(
            RecipeJsonLd.parseFromHtml(page(minimalRecipe), createdAtEpochMillis = 1L),
        )
        val spaghetti = recipe.ingredients[0]
        assertEquals(400.0, spaghetti.amount!!, 0.001)
        assertEquals("g", spaghetti.unit)
        assertEquals("Spaghetti", spaghetti.displayName)

        val onions = recipe.ingredients[1]
        assertEquals(2.0, onions.amount!!, 0.001)
        assertNull(onions.unit)
        assertEquals("Zwiebeln", onions.displayName)

        // "to taste" lines carry no amount and must survive as presence-only ingredients.
        val salt = recipe.ingredients[2]
        assertNull(salt.amount)
        assertEquals("Salz", salt.displayName)
    }

    @Test
    fun `finds a recipe nested in an at-graph wrapper`() {
        val graph = """
            {"@context":"https://schema.org","@graph":[
              {"@type":"WebSite","name":"Some Blog"},
              $minimalRecipe
            ]}
        """.trimIndent()
        val recipe = RecipeJsonLd.parseFromHtml(page(graph), createdAtEpochMillis = 1L)
        assertEquals("Tomato Pasta", recipe?.title)
    }

    @Test
    fun `finds a recipe in a top-level array`() {
        val recipe = RecipeJsonLd.parseFromHtml(
            page("""[{"@type":"Organization","name":"X"}, $minimalRecipe]"""),
            createdAtEpochMillis = 1L,
        )
        assertEquals("Tomato Pasta", recipe?.title)
    }

    @Test
    fun `accepts an at-type array that includes Recipe`() {
        val multiType = minimalRecipe.replace(
            """"@type": "Recipe",""",
            """"@type": ["Recipe", "NewsArticle"],""",
        )
        assertEquals(
            "Tomato Pasta",
            RecipeJsonLd.parseFromHtml(page(multiType), createdAtEpochMillis = 1L)?.title,
        )
    }

    @Test
    fun `splits a single instruction blob on newlines`() {
        // Written out rather than regex-substituted into minimalRecipe: Regex.replace treats
        // backslashes in the replacement as escapes and would eat the \n separators under test.
        val blob = """
            {
              "@type": "Recipe",
              "name": "Blob",
              "recipeIngredient": ["400 g Spaghetti"],
              "recipeInstructions": "Nudeln kochen.\nZwiebeln anbraten.\nServieren."
            }
        """.trimIndent()
        val recipe = RecipeJsonLd.parseFromHtml(page(blob), createdAtEpochMillis = 1L)
        assertEquals(
            listOf("Nudeln kochen.", "Zwiebeln anbraten.", "Servieren."),
            recipe?.steps,
        )
    }

    @Test
    fun `flattens HowToSection nesting`() {
        val sectioned = minimalRecipe.replace(
            Regex(""""recipeInstructions": \[.*?\]""", RegexOption.DOT_MATCHES_ALL),
            """"recipeInstructions": [
                 {"@type":"HowToSection","name":"Sauce","itemListElement":[
                   {"@type":"HowToStep","text":"Passata aufkochen."}
                 ]},
                 {"@type":"HowToSection","name":"Pasta","itemListElement":[
                   {"@type":"HowToStep","text":"Nudeln garen."}
                 ]}
               ]""",
        )
        val recipe = RecipeJsonLd.parseFromHtml(page(sectioned), createdAtEpochMillis = 1L)
        assertEquals(listOf("Passata aufkochen.", "Nudeln garen."), recipe?.steps)
    }

    @Test
    fun `strips html and decodes entities in steps`() {
        val messy = minimalRecipe.replace(
            """{"@type": "HowToStep", "text": "Nudeln kochen."}""",
            """{"@type": "HowToStep", "text": "<p>Nudeln &amp; So&#223;e kochen.</p>"}""",
        )
        val recipe = RecipeJsonLd.parseFromHtml(page(messy), createdAtEpochMillis = 1L)
        assertEquals("Nudeln & Soße kochen.", recipe?.steps?.first())
    }

    @Test
    fun `falls back to prep plus cook when totalTime is absent`() {
        val split = minimalRecipe.replace(
            """"totalTime": "PT35M",""",
            """"prepTime": "PT15M", "cookTime": "PT1H",""",
        )
        assertEquals(75, RecipeJsonLd.parseFromHtml(page(split), createdAtEpochMillis = 1L)?.prepMinutes)
    }

    @Test
    fun `parses iso durations`() {
        assertEquals(30, RecipeJsonLd.parseIsoDurationMinutes("PT30M"))
        assertEquals(90, RecipeJsonLd.parseIsoDurationMinutes("PT1H30M"))
        assertEquals(60, RecipeJsonLd.parseIsoDurationMinutes("PT1H"))
        assertNull(RecipeJsonLd.parseIsoDurationMinutes("PT0M"))
        assertNull(RecipeJsonLd.parseIsoDurationMinutes(""))
        assertNull(RecipeJsonLd.parseIsoDurationMinutes("not a duration"))
    }

    @Test
    fun `parses fraction and decimal amounts`() {
        assertEquals(0.5, RecipeJsonLd.parseIngredientLine("½ TL Salz")?.amount!!, 0.001)
        assertEquals(0.5, RecipeJsonLd.parseIngredientLine("1/2 cup sugar")?.amount!!, 0.001)
        assertEquals(1.5, RecipeJsonLd.parseIngredientLine("1,5 l Wasser")?.amount!!, 0.001)
        assertEquals(1.5, RecipeJsonLd.parseIngredientLine("1.5 l Wasser")?.amount!!, 0.001)
    }

    @Test
    fun `takes the low end of a range`() {
        val parsed = requireNotNull(RecipeJsonLd.parseIngredientLine("2-3 Eier"))
        assertEquals(2.0, parsed.amount!!, 0.001)
        assertEquals("Eier", parsed.displayName)
    }

    @Test
    fun `keeps unconvertible measure words as units`() {
        val parsed = requireNotNull(RecipeJsonLd.parseIngredientLine("2 EL Olivenöl"))
        assertEquals("EL", parsed.unit)
        assertEquals("Olivenöl", parsed.displayName)
    }

    @Test
    fun `lifts an inline optional marker onto the flag`() {
        val parenthesised = requireNotNull(
            RecipeJsonLd.parseIngredientLine("large handful basil leaves torn (optional)"),
        )
        assertTrue(parenthesised.optional)
        assertEquals("large handful basil leaves torn", parenthesised.displayName)

        val trailing = requireNotNull(RecipeJsonLd.parseIngredientLine("50 g Parmesan, optional"))
        assertTrue(trailing.optional)
        assertEquals("Parmesan", trailing.displayName)

        // The word must be a suffix, not anywhere in the name.
        val notOptional = requireNotNull(RecipeJsonLd.parseIngredientLine("2 Eier"))
        assertTrue(!notOptional.optional)
    }

    @Test
    fun `does not mistake an ingredient name for a unit`() {
        val parsed = requireNotNull(RecipeJsonLd.parseIngredientLine("3 Tomaten"))
        assertNull(parsed.unit)
        assertEquals("Tomaten", parsed.displayName)
    }

    @Test
    fun `returns null rather than throwing on unusable input`() {
        assertNull(RecipeJsonLd.parseFromHtml("<html><body>no markup</body></html>", 1L))
        assertNull(RecipeJsonLd.parseFromHtml(page("{not json at all"), 1L))
        // A Recipe node with no ingredients is not importable.
        assertNull(
            RecipeJsonLd.parseFromHtml(
                page("""{"@type":"Recipe","name":"Empty","recipeInstructions":["x"]}"""),
                1L,
            ),
        )
    }

    @Test
    fun `skips a non-recipe block and keeps scanning later ones`() {
        val html = """
            <html><head>
            <script type="application/ld+json">{"@type":"Organization","name":"X"}</script>
            <script type="application/ld+json">$minimalRecipe</script>
            </head><body></body></html>
        """.trimIndent()
        assertEquals("Tomato Pasta", RecipeJsonLd.parseFromHtml(html, 1L)?.title)
    }

    @Test
    fun `htmlToPlainText drops script and style bodies`() {
        val text = RecipeJsonLd.htmlToPlainText(
            """<html><head><style>.a{color:red}</style><script>var x=1;</script></head>
               <body><h1>Titel</h1><p>Erst kochen.</p><p>Dann essen.</p></body></html>""",
        )
        assertTrue(text.contains("Titel"))
        assertTrue(text.contains("Erst kochen."))
        assertTrue("color:red" !in text)
        assertTrue("var x" !in text)
    }
}
