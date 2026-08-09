package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.InventoryItem
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRecipePromptsTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.of(2026, 8, 8)

    @Test
    fun `system prompt carries the concept catalogue and rules`() {
        val prompt = OpenAiRecipePrompts.suggestSystemPrompt()

        assertTrue(prompt.contains("TOMATO"))
        assertTrue(prompt.contains("SPINACH"))
        assertTrue(prompt.contains("conceptId"))
        // The trimIndent gotcha: literal rule lines must not keep their source indentation.
        assertTrue(prompt.lines().none { it.startsWith("        - Prefer ingredients") })
    }

    @Test
    fun `inventory snapshot renders one pipe-separated line per item`() {
        val expiring = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val snapshot = OpenAiRecipePrompts.inventorySnapshot(
            listOf(
                InventoryItem(
                    name = "Bio Tomaten",
                    foodConceptId = "TOMATO",
                    quantity = 500.0,
                    unit = "g",
                    storageLocation = "FRIDGE",
                    expiresAtEpochMillis = expiring,
                ),
                InventoryItem(name = "Unbekannt", quantity = 1.0),
            ),
            today,
            zone,
        )

        val lines = snapshot.lines()
        assertEquals(2, lines.size)
        assertEquals("TOMATO | Bio Tomaten | 500.0 g | Fridge | expires in 2 days", lines[0])
        assertEquals("- | Unbekannt | 1.0 | - | -", lines[1])
    }

    @Test
    fun `schema requires the recipe fields`() {
        val schema = OpenAiRecipePrompts.buildSchema()

        assertEquals("object", schema.getString("type"))
        val recipeSchema = schema.getJSONObject("properties")
            .getJSONObject("recipes")
            .getJSONObject("items")
        val required = recipeSchema.getJSONArray("required")
        val names = List(required.length()) { required.getString(it) }
        assertTrue(
            names.containsAll(
                listOf("title", "servings", "prepMinutes", "tags", "ingredients", "steps"),
            ),
        )
    }

    @Test
    fun `parses a canned structured answer into recipes`() {
        val content = JSONObject()
            .put(
                "recipes",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Spinach Omelette")
                        .put("servings", 2)
                        .put("prepMinutes", 15)
                        .put("tags", JSONArray(listOf("quick")))
                        .put(
                            "ingredients",
                            JSONArray()
                                .put(ingredientJson("EGGS", "Eggs", 3.0, "pc", false))
                                .put(ingredientJson("SPINACH", "Spinach", 100.0, "g", false))
                                .put(ingredientJson(null, "Salt", null, null, true)),
                        )
                        .put("steps", JSONArray(listOf("Whisk.", "Fry."))),
                ),
            )
            .toString()

        val recipes = OpenAiRecipePrompts.parseRecipes(
            content = content,
            source = RecipeSource.AI,
            createdAtEpochMillis = 42L,
        )

        val recipe = recipes.single()
        assertTrue(recipe.id.startsWith("ai_"))
        assertEquals("Spinach Omelette", recipe.title)
        assertEquals(RecipeSource.AI, recipe.source)
        assertEquals(42L, recipe.createdAtEpochMillis)
        // "Salt" resolves via the English display-name fallback even with a null claimed id.
        assertEquals(listOf("EGGS", "SPINACH", "SALT"), recipe.ingredients.map { it.foodConceptId })
        assertTrue(recipe.ingredients[2].optional)
    }

    @Test
    fun `invalid concept ids fall back to name matching or null`() {
        val content = JSONObject()
            .put(
                "recipes",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Test")
                        .put("servings", 2)
                        .put("prepMinutes", 10)
                        .put("tags", JSONArray())
                        .put(
                            "ingredients",
                            JSONArray()
                                // Hallucinated id, but the German name keyword-matches ONION.
                                .put(ingredientJson("NOT_A_CONCEPT", "Zwiebel", 1.0, "pc", false))
                                // Hallucinated id, English display name matches SPINACH.
                                .put(ingredientJson("SPINAT_X", "Spinach", 100.0, "g", false))
                                // No match at all.
                                .put(ingredientJson("NOPE", "Unicorn dust", null, null, false)),
                        )
                        .put("steps", JSONArray(listOf("Mix."))),
                ),
            )
            .toString()

        val recipe = OpenAiRecipePrompts.parseRecipes(content, RecipeSource.AI, 0L).single()

        assertEquals("ONION", recipe.ingredients[0].foodConceptId)
        assertEquals("SPINACH", recipe.ingredients[1].foodConceptId)
        assertNull(recipe.ingredients[2].foodConceptId)
    }

    @Test
    fun `malformed recipes are skipped and garbage input yields nothing`() {
        val content = JSONObject()
            .put(
                "recipes",
                JSONArray()
                    .put(JSONObject().put("title", "No ingredients"))
                    .put(
                        JSONObject()
                            .put("title", "Fine")
                            .put("servings", 1)
                            .put("prepMinutes", 5)
                            .put("tags", JSONArray())
                            .put(
                                "ingredients",
                                JSONArray().put(ingredientJson("EGGS", "Eggs", 2.0, "pc", false)),
                            )
                            .put("steps", JSONArray(listOf("Cook."))),
                    ),
            )
            .toString()

        assertEquals(
            listOf("Fine"),
            OpenAiRecipePrompts.parseRecipes(content, RecipeSource.AI, 0L).map { it.title },
        )
        assertTrue(OpenAiRecipePrompts.parseRecipes("garbage", RecipeSource.AI, 0L).isEmpty())
    }

    @Test
    fun `image prompt names the dish and its main ingredients`() {
        val recipe = Recipe(
            id = "r",
            title = "Tomato Soup",
            ingredients = listOf(
                RecipeIngredient("CANNED_TOMATOES", "Canned tomatoes", 800.0, "g"),
                RecipeIngredient("CREAM", "Cream", 100.0, "ml", optional = true),
            ),
            steps = listOf("Cook."),
            prepMinutes = 15,
        )

        val prompt = OpenAiRecipePrompts.imagePrompt(recipe)

        assertTrue(prompt.contains("Tomato Soup"))
        assertTrue(prompt.contains("Canned tomatoes"))
        assertTrue(prompt.contains("no text"))
    }

    private fun ingredientJson(
        conceptId: String?,
        name: String,
        amount: Double?,
        unit: String?,
        optional: Boolean,
    ): JSONObject = JSONObject()
        .put("conceptId", conceptId ?: JSONObject.NULL)
        .put("name", name)
        .put("amount", amount ?: JSONObject.NULL)
        .put("unit", unit ?: JSONObject.NULL)
        .put("optional", optional)
}
