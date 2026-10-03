package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.InventoryItem
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeGenerationTest {

    private fun recipe(id: String, minutes: Int, tags: List<String> = emptyList(), conceptId: String = "EGGS") = Recipe(
        id = id,
        title = id,
        ingredients = listOf(RecipeIngredient(conceptId, conceptId, 1.0, "pc")),
        steps = listOf("Cook."),
        prepMinutes = minutes,
        tags = tags,
        source = RecipeSource.AI,
    )

    @Test
    fun `request and result round trip through json`() {
        val result = RecipeGenerationResult(
            id = "g1",
            request = RecipeGenerationRequest(
                selectedInventoryItemIds = setOf("i1", "i2"),
                preferences = setOf(GenerationPreference.VEGAN, GenerationPreference.SPICY),
                time = GenerationTime.UNDER_30,
                freeText = "something warming",
                count = 4,
            ),
            recipeIds = listOf("a", "b"),
            createdAtEpochMillis = 99L,
        )

        assertEquals(result, result.toJson().toRecipeGenerationResultOrNull())
    }

    @Test
    fun `unknown enum names and out of range counts degrade gracefully`() {
        val json = JSONObject()
            .put("preferences", org.json.JSONArray(listOf("VEGAN", "NOPE")))
            .put("time", "LATER")
            .put("count", 99)
        val request = json.toRecipeGenerationRequestOrNull()!!

        assertEquals(setOf(GenerationPreference.VEGAN), request.preferences)
        assertEquals(GenerationTime.ANY, request.time)
        assertEquals(RecipeGenerationRequest.MAX_COUNT, request.count)
        assertNull(JSONObject().put("id", "x").toRecipeGenerationResultOrNull())
    }

    @Test
    fun `summary names what was asked for`() {
        assertEquals("From your inventory", RecipeGenerationRequest().summary())
        assertEquals(
            "2 ingredients · Vegetarian · < 30 min · “quick”",
            RecipeGenerationRequest(
                selectedInventoryItemIds = setOf("a", "b"),
                preferences = setOf(GenerationPreference.VEGETARIAN),
                time = GenerationTime.UNDER_30,
                freeText = " quick ",
            ).summary(),
        )
    }

    @Test
    fun `grouping sorts best by score and filters quick and protein`() {
        val matcher = RecipeMatcher()
        val inventory = listOf(InventoryItem(name = "Eggs", foodConceptId = "EGGS", quantity = 6.0, unit = "pc"))
        val matches = listOf(
            recipe("slow", 90, tags = listOf("High Protein")),
            recipe("fast", 15),
            recipe("labelled", 45, tags = listOf("ai:quick", "ai:protein")),
            recipe("missing", 10, conceptId = "TOFU"),
        ).map { matcher.matchOne(it, inventory, LocalDate.of(2026, 8, 21)) }

        val groups = GenerationGrouping.group(matches)

        assertEquals(4, groups.getValue(GenerationResultTab.BEST_MATCH).size)
        assertEquals("missing", groups.getValue(GenerationResultTab.BEST_MATCH).last().recipe.id)
        assertEquals(listOf("missing", "fast", "labelled"), groups.getValue(GenerationResultTab.QUICK_EASY).map { it.recipe.id })
        assertEquals(setOf("slow", "labelled"), groups.getValue(GenerationResultTab.HIGH_PROTEIN).map { it.recipe.id }.toSet())
        assertTrue(GenerationGrouping.isGoodMatch(matches.first()))
        assertFalse(GenerationGrouping.isGoodMatch(matches.last()))
    }
}
