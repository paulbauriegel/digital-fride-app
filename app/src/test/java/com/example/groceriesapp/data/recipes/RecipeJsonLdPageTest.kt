package com.example.groceriesapp.data.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the parser against a whole recipe page rather than the bare JSON-LD in [RecipeJsonLdTest]:
 * minified markup with scripts and styles, several JSON-LD blocks (an Organization next to an
 * @graph holding WebSite, BreadcrumbList and the Recipe), HTML inside step text, and entity-encoded
 * fractions and dashes. The page is synthetic (written for this project) so it can be published.
 *
 * The fixture is checked in so the test is hermetic and never touches the network.
 */
class RecipeJsonLdPageTest {

    private fun fixture(): String? = javaClass.classLoader
        ?.getResourceAsStream("recipe_page.html")
        ?.bufferedReader()
        ?.use { it.readText() }

    @Test
    fun `extracts a usable recipe from a full page`() {
        val html = fixture()
        assumeTrue("fixture missing", html != null)
        requireNotNull(html)

        val recipe = RecipeJsonLd.parseFromHtml(html, createdAtEpochMillis = 1L)
        requireNotNull(recipe) { "no recipe parsed from the page" }

        assertEquals("Red lentil and tomato soup", recipe.title)
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
