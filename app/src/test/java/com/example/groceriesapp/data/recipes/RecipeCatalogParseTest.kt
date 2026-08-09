package com.example.groceriesapp.data.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeCatalogParseTest {

    @Test
    fun `parses a valid recipe`() {
        val recipes = RecipeCatalog.parse(
            """
            {"schemaVersion": 1, "recipes": [{
                "id": "test", "title": "Test Dish", "servings": 3, "prepMinutes": 20,
                "tags": ["quick"], "image": "asset:recipes/images/test.webp",
                "ingredients": [
                    {"conceptId": "TOMATO", "name": "Tomatoes", "amount": 400, "unit": "g"},
                    {"conceptId": null, "name": "Secret spice", "amount": null, "unit": null, "optional": true}
                ],
                "steps": ["Chop.", "Cook."]
            }]}
            """.trimIndent(),
        )

        assertEquals(1, recipes.size)
        val recipe = recipes.single()
        assertEquals("test", recipe.id)
        assertEquals("Test Dish", recipe.title)
        assertEquals(3, recipe.servings)
        assertEquals(20, recipe.prepMinutes)
        assertEquals(listOf("quick"), recipe.tags)
        assertEquals("asset:recipes/images/test.webp", recipe.imageRef)
        assertEquals(RecipeSource.CURATED, recipe.source)
        assertEquals(2, recipe.ingredients.size)
        assertEquals("TOMATO", recipe.ingredients[0].foodConceptId)
        assertEquals(400.0, recipe.ingredients[0].amount!!, 1e-9)
        assertNull(recipe.ingredients[1].foodConceptId)
        assertNull(recipe.ingredients[1].amount)
        assertTrue(recipe.ingredients[1].optional)
        assertEquals(listOf("Chop.", "Cook."), recipe.steps)
    }

    @Test
    fun `skips malformed entries but keeps valid ones`() {
        val recipes = RecipeCatalog.parse(
            """
            {"recipes": [
                {"id": "broken", "title": "No steps or ingredients"},
                {"id": "", "title": "Blank id", "prepMinutes": 10, "ingredients": [], "steps": []},
                {"id": "ok", "title": "Fine", "prepMinutes": 10,
                 "ingredients": [{"conceptId": "EGGS", "name": "Eggs", "amount": 2, "unit": "pc"}],
                 "steps": ["Cook."]}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("ok"), recipes.map { it.id })
    }

    @Test
    fun `tolerates unknown keys and missing optional fields`() {
        val recipes = RecipeCatalog.parse(
            """
            {"recipes": [{
                "id": "min", "title": "Minimal", "prepMinutes": 5, "futureField": {"x": 1},
                "ingredients": [{"name": "Salt"}], "steps": ["Season."]
            }]}
            """.trimIndent(),
        )

        val recipe = recipes.single()
        assertEquals(2, recipe.servings)
        assertNull(recipe.imageRef)
        assertNull(recipe.ingredients.single().foodConceptId)
    }

    @Test
    fun `returns empty list for garbage input`() {
        assertTrue(RecipeCatalog.parse("not json").isEmpty())
        assertTrue(RecipeCatalog.parse("{}").isEmpty())
    }

    @Test
    fun `recipe json round-trips through toJson and back`() {
        val original = Recipe(
            id = "round",
            title = "Round Trip",
            ingredients = listOf(
                RecipeIngredient("ONION", "Onion", 1.0, "pc"),
                RecipeIngredient(null, "Mystery", null, null, optional = true),
            ),
            steps = listOf("One.", "Two."),
            servings = 4,
            prepMinutes = 33,
            tags = listOf("a", "b"),
            imageRef = "file:round.webp",
            source = RecipeSource.USER,
            createdAtEpochMillis = 1_700_000_000_000L,
        )

        val restored = original.toJson().toRecipeOrNull()

        assertEquals(original, restored)
    }
}
