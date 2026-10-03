package com.example.groceriesapp.data.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesRecipeTextExtractorTest {

    private val extractor = RulesRecipeTextExtractor()

    private fun parse(text: String): Recipe? = extractor.parse(text.trimIndent(), nowEpochMillis = 42L)

    @Test
    fun `german cookbook page with headers`() {
        val recipe = parse(
            """
            Kartoffelgratin
            Für 4 Personen · 60 Minuten

            Zutaten
            1 kg Kartoffeln
            200 ml Sahne
            100 g Gouda, gerieben
            Salz, Pfeffer

            Zubereitung
            1. Kartoffeln schälen und in dünne Scheiben schneiden.
            2. Sahne mit Salz und Pfeffer würzen und über die Kartoffeln
            gießen.
            3. Mit Käse bestreuen und 45 Minuten backen.
            """,
        )

        assertNotNull(recipe)
        recipe!!
        assertEquals("Kartoffelgratin", recipe.title)
        assertEquals(4, recipe.servings)
        assertEquals(60, recipe.prepMinutes)
        assertEquals(listOf("Kartoffeln", "Sahne", "Gouda, gerieben", "Salz, Pfeffer"), recipe.ingredients.map { it.displayName })
        assertEquals(1.0, recipe.ingredients[0].amount!!, 1e-9)
        assertEquals("kg", recipe.ingredients[0].unit)
        assertEquals(3, recipe.steps.size)
        assertTrue(recipe.steps[1].endsWith("über die Kartoffeln gießen."))
        assertEquals(RecipeSource.USER, recipe.source)
        assertEquals(42L, recipe.createdAtEpochMillis)
        assertTrue(recipe.id.startsWith("user_"))
    }

    @Test
    fun `english card without headers uses bullets and numbering`() {
        val recipe = parse(
            """
            Quick Pancakes
            Serves 2, 15 min
            • 1 cup flour
            • 1 egg
            • 200 ml milk
            1) Whisk everything together until smooth.
            2) Fry ladlefuls in a hot pan until golden on both sides.
            """,
        )

        assertNotNull(recipe)
        recipe!!
        assertEquals("Quick Pancakes", recipe.title)
        assertEquals(2, recipe.servings)
        assertEquals(15, recipe.prepMinutes)
        assertEquals(listOf("flour", "egg", "milk"), recipe.ingredients.map { it.displayName })
        assertEquals(2, recipe.steps.size)
        assertEquals("Whisk everything together until smooth.", recipe.steps[0])
    }

    @Test
    fun `hours add to minutes and steps section may come first`() {
        val recipe = parse(
            """
            Slow Beef Stew
            Method
            Brown the beef, then simmer for 1 h 30 min.
            Ingredients
            500 g beef
            2 onions
            """,
        )

        assertNotNull(recipe)
        assertEquals(90, recipe!!.prepMinutes)
        assertEquals(listOf("beef", "onions"), recipe.ingredients.map { it.displayName })
        assertEquals(1, recipe.steps.size)
    }

    @Test
    fun `long unnumbered prose after the ingredients becomes steps`() {
        val recipe = parse(
            """
            Tomato Soup
            4 tomatoes
            1 onion
            Chop the tomatoes and onion, then simmer in stock for twenty minutes.
            Blend until smooth and season to taste.
            """,
        )

        assertNotNull(recipe)
        assertEquals(2, recipe!!.steps.size)
        assertEquals(2, recipe.ingredients.size)
        // No minute figure in digits, so the default applies.
        assertEquals(30, recipe.prepMinutes)
    }

    @Test
    fun `garbage text is not a recipe`() {
        assertNull(parse("Lorem ipsum dolor sit amet"))
        assertNull(parse(""))
        assertNull(parse("Page 42\nChapter three"))
    }

    @Test
    fun `title falls back to the first line when nothing else qualifies`() {
        val recipe = parse(
            """
            2 eggs
            1 tbsp butter
            1. Scramble.
            """,
        )
        assertEquals("2 eggs", recipe?.title)
    }
}
