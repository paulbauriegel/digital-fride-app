package com.example.groceriesapp.data.recipes

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the parser against a real, unmodified recipe page captured from the web, rather than the
 * hand-written JSON-LD in [RecipeJsonLdTest]. Synthetic fixtures only prove the parser handles the
 * shapes I thought of; this proves it survives a production page — minified markup, a 600 KB body,
 * unrelated JSON-LD nodes, HTML inside step text, and entity-encoded fractions.
 *
 * The fixture is checked in so the test is hermetic and never touches the network.
 */
class RecipeJsonLdRealPageTest {

    private fun fixture(): String? = javaClass.classLoader
        ?.getResourceAsStream("real_recipe_page.html")
        ?.bufferedReader()
        ?.use { it.readText() }

    @Test
    fun `extracts a usable recipe from a real page`() {
        val html = fixture()
        assumeTrue("fixture missing", html != null)
        requireNotNull(html)

        val recipe = RecipeJsonLd.parseFromHtml(html, createdAtEpochMillis = 1L)
        requireNotNull(recipe) { "no recipe parsed from the real page" }

        assertTrue("title was blank", recipe.title.isNotBlank())
        assertTrue("expected several ingredients", recipe.ingredients.size >= 5)
        assertTrue("expected several steps", recipe.steps.size >= 3)
        assertTrue("servings out of range", recipe.servings in 1..100)
        assertTrue("prepMinutes out of range", recipe.prepMinutes in 1..1440)

        // Steps must be prose, not leftover markup or empty strings.
        recipe.steps.forEach { step ->
            assertTrue("step contained a tag: $step", '<' !in step)
            assertTrue("step was blank", step.isNotBlank())
        }
        // Ingredient names must never come through empty, and most should carry an amount.
        recipe.ingredients.forEach { assertTrue("blank ingredient", it.displayName.isNotBlank()) }
        val withAmounts = recipe.ingredients.count { it.amount != null }
        assertTrue(
            "only $withAmounts of ${recipe.ingredients.size} ingredients parsed an amount",
            withAmounts >= recipe.ingredients.size / 2,
        )

        println("parsed: ${recipe.title} | ${recipe.servings} servings | ${recipe.prepMinutes} min")
        println("ingredients: " + recipe.ingredients.joinToString(" / ") {
            listOfNotNull(it.amount?.toString(), it.unit, it.displayName).joinToString(" ")
        })
        println("steps: ${recipe.steps.size}")
    }
}
