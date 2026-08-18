package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import com.example.groceriesapp.data.taxonomy.StorageLocation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Prompt building and response parsing for the OpenAI recipe features. Pure and unit-testable;
 * the network call itself lives in
 * [com.example.groceriesapp.data.network.OpenAiChatClient]. The model is told to emit our
 * canonical concept IDs directly (the full catalogue rides along in the system prompt), which
 * sidesteps German/English ingredient-name matching entirely; [parseRecipes] still validates every
 * returned id and falls back to keyword matching before giving up on a mapping.
 */
object OpenAiRecipePrompts {

    const val SUGGESTION_COUNT = 5

    // Assembled by concatenation: trimIndent on a template with interpolated multi-line content
    // would see the inserted zero-indent lines and stop trimming the literal ones.
    private val sharedRules =
        """
        You write practical home-cooking recipes for a German household grocery app.

        Every ingredient has an optional "conceptId" that MUST be either null or exactly one ID
        from this catalogue (format: ID — name):
        """.trimIndent() +
            "\n\n" + conceptCatalogLines() + "\n\n" +
            """
            Rules:
            - Prefer ingredients from the user's inventory, especially items that expire soon.
            - Keep recipes realistic and simple; 3-8 numbered steps, everyday techniques.
            - amounts: use unit "g", "ml", or "pc" where sensible; null unit and amount for "to taste".
            - Mark pantry staples the user may lack (spices, oil) as optional rather than dropping them.
            - Write titles, ingredient names, and steps in English.
            - servings is the number of people the amounts feed; prepMinutes the total time in minutes.
            """.trimIndent()

    fun suggestSystemPrompt(): String = sharedRules

    fun suggestUserContent(inventoryPrompt: String, count: Int = SUGGESTION_COUNT): String = """
        Suggest $count different recipes that cook primarily from this inventory. Vary the style
        (e.g. pasta, soup, salad, pan dish). Use up soon-to-expire items first.

        Inventory (conceptId | name | amount | location | expiry):
        $inventoryPrompt
    """.trimIndent()

    /**
     * A user-driven request for exactly one recipe: free text, an attached photo, or both.
     *
     * Unlike [suggestUserContent] the inventory is context rather than the brief — someone who asks
     * for lasagne wants lasagne, with a shopping gap, not the nearest thing the fridge allows.
     */
    fun requestUserContent(
        request: String,
        inventoryPrompt: String,
        hasPhoto: Boolean,
    ): String = buildString {
        append("Write exactly ONE recipe for the request below. Return a single entry in ")
        append("\"recipes\".\n\n")
        if (hasPhoto) {
            append(
                "The attached photo is part of the request. It may show a finished dish to " +
                    "recreate, loose ingredients to cook with, or a written recipe to " +
                    "transcribe — work out which, and name the dish in the title.\n\n",
            )
        }
        if (request.isNotBlank()) {
            append("What the user asked for:\n")
            append(request.trim())
            append("\n\n")
        }
        append(
            "Follow the request even when the inventory does not cover it: list whatever the " +
                "dish needs and let the user shop for the rest. The inventory below is only " +
                "context for sensible substitutions and amounts.\n\n",
        )
        append("Inventory (conceptId | name | amount | location | expiry):\n")
        append(inventoryPrompt)
    }

    fun adaptUserContent(recipeJson: String, inventoryPrompt: String): String = """
        Adapt the following recipe so it uses only what this inventory offers. Substitute or drop
        what is missing, keep the spirit of the dish, and scale amounts to what is actually there
        when short. Return exactly one recipe titled like the original with a hint that it was
        adapted, e.g. "Tomato Soup (pantry version)".

        Recipe:
        $recipeJson

        Inventory (conceptId | name | amount | location | expiry):
        $inventoryPrompt
    """.trimIndent()

    fun qaSystemPrompt(): String = """
        You answer questions about one specific home-cooking recipe for a German household grocery
        app. Answer only what is asked, in 1-4 short sentences, grounded in the recipe details and
        the user's inventory given below. If a question needs information the recipe doesn't cover
        (e.g. nutrition facts), say so briefly instead of guessing. Answer in the same language as
        the question.
    """.trimIndent()

    fun qaUserContent(
        recipe: Recipe,
        inventoryPrompt: String,
        history: List<RecipeQaTurn>,
        question: String,
    ): String = buildString {
        append("Recipe:\n")
        append(recipeSnapshot(recipe))
        append("\n\nInventory (conceptId | name | amount | location | expiry):\n")
        append(inventoryPrompt)
        if (history.isNotEmpty()) {
            append("\n\nEarlier questions about this recipe in this conversation:\n")
            history.forEach { turn ->
                append("Q: ${turn.question}\nA: ${turn.answer}\n")
            }
        }
        append("\nNew question:\n")
        append(question)
    }

    fun buildQaSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put("required", JSONArray(listOf("answer")))
        .put("properties", JSONObject().put("answer", JSONObject().put("type", "string")))

    fun parseAnswer(content: String): String? =
        runCatching { JSONObject(content).optString("answer") }.getOrNull()
            ?.takeIf(String::isNotBlank)

    private fun recipeSnapshot(recipe: Recipe): String = buildString {
        append("${recipe.title} — serves ${recipe.servings}, ${recipe.prepMinutes} min\n")
        append("Ingredients:\n")
        recipe.ingredients.forEach { ingredient ->
            val amount = buildString {
                ingredient.amount?.let { append(it) }
                ingredient.unit?.takeIf(String::isNotBlank)?.let { append(" $it") }
            }.trim()
            append("- ${ingredient.displayName}")
            if (amount.isNotEmpty()) append(" ($amount)")
            if (ingredient.optional) append(" [optional]")
            append("\n")
        }
        append("Steps:\n")
        recipe.steps.forEachIndexed { index, step -> append("${index + 1}. $step\n") }
    }

    fun importUserContent(ocrText: String): String = """
        The following is OCR text of a photographed recipe (cookbook page or handwritten card,
        German or English, possibly with recognition errors). Reconstruct it as exactly one
        structured recipe. Keep the original language's title translated to English, fix obvious
        OCR mistakes, and estimate prepMinutes and servings if absent.

        OCR text:
        $ocrText
    """.trimIndent()

    /** `conceptId | name | quantity unit | location | expiry` — one line per inventory row. */
    fun inventorySnapshot(
        inventory: List<InventoryItem>,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = inventory.joinToString("\n") { item ->
        val amount = buildString {
            append(item.quantity)
            item.unit?.takeIf(String::isNotBlank)?.let { append(" $it") }
        }
        val location = item.storageLocation
            ?.let { StorageLocation.fromStorageValue(it)?.displayName }
            ?: "-"
        val expiry = item.expiresAtEpochMillis?.let { millis ->
            val days = ChronoUnit.DAYS.between(
                today,
                Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate(),
            )
            when {
                days < 0 -> "expired"
                days == 0L -> "expires today"
                days == 1L -> "expires tomorrow"
                else -> "expires in $days days"
            }
        } ?: "-"
        "${item.foodConceptId ?: "-"} | ${item.name} | $amount | $location | $expiry"
    }

    fun buildSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put("required", JSONArray(listOf("recipes")))
        .put(
            "properties",
            JSONObject().put(
                "recipes",
                JSONObject()
                    .put("type", "array")
                    .put(
                        "items",
                        JSONObject()
                            .put("type", "object")
                            .put("additionalProperties", false)
                            .put(
                                "required",
                                JSONArray(
                                    listOf(
                                        "title", "servings", "prepMinutes",
                                        "tags", "ingredients", "steps",
                                    ),
                                ),
                            )
                            .put(
                                "properties",
                                JSONObject()
                                    .put("title", JSONObject().put("type", "string"))
                                    .put("servings", JSONObject().put("type", "integer"))
                                    .put("prepMinutes", JSONObject().put("type", "integer"))
                                    .put(
                                        "tags",
                                        JSONObject()
                                            .put("type", "array")
                                            .put("items", JSONObject().put("type", "string")),
                                    )
                                    .put(
                                        "ingredients",
                                        JSONObject()
                                            .put("type", "array")
                                            .put(
                                                "items",
                                                JSONObject()
                                                    .put("type", "object")
                                                    .put("additionalProperties", false)
                                                    .put(
                                                        "required",
                                                        JSONArray(
                                                            listOf(
                                                                "conceptId", "name", "amount",
                                                                "unit", "optional",
                                                            ),
                                                        ),
                                                    )
                                                    .put(
                                                        "properties",
                                                        JSONObject()
                                                            .put(
                                                                "conceptId",
                                                                JSONObject().put(
                                                                    "type",
                                                                    JSONArray(listOf("string", "null")),
                                                                ),
                                                            )
                                                            .put(
                                                                "name",
                                                                JSONObject().put("type", "string"),
                                                            )
                                                            .put(
                                                                "amount",
                                                                JSONObject().put(
                                                                    "type",
                                                                    JSONArray(listOf("number", "null")),
                                                                ),
                                                            )
                                                            .put(
                                                                "unit",
                                                                JSONObject().put(
                                                                    "type",
                                                                    JSONArray(listOf("string", "null")),
                                                                ),
                                                            )
                                                            .put(
                                                                "optional",
                                                                JSONObject().put("type", "boolean"),
                                                            ),
                                                    ),
                                            ),
                                    )
                                    .put(
                                        "steps",
                                        JSONObject()
                                            .put("type", "array")
                                            .put("items", JSONObject().put("type", "string")),
                                    ),
                            ),
                    ),
            ),
        )

    /**
     * Parses a schema-constrained answer into [Recipe]s. Every conceptId is validated against the
     * catalogue, falling back to keyword-matching the ingredient name, else null. Malformed
     * entries are skipped, never fatal.
     */
    fun parseRecipes(
        content: String,
        source: RecipeSource,
        createdAtEpochMillis: Long,
        idPrefix: String = "ai_",
    ): List<Recipe> {
        val array = runCatching { JSONObject(content).optJSONArray("recipes") }.getOrNull()
            ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                parseRecipe(entry, source, createdAtEpochMillis, idPrefix)?.let(::add)
            }
        }
    }

    private fun parseRecipe(
        entry: JSONObject,
        source: RecipeSource,
        createdAtEpochMillis: Long,
        idPrefix: String,
    ): Recipe? = runCatching {
        val ingredientsArray = entry.getJSONArray("ingredients")
        val ingredients = buildList {
            for (i in 0 until ingredientsArray.length()) {
                val ingredient = ingredientsArray.optJSONObject(i) ?: continue
                val name = ingredient.getString("name").trim()
                if (name.isEmpty()) continue
                val claimedId = ingredient.optStringOrNull("conceptId")
                add(
                    RecipeIngredient(
                        foodConceptId = resolveConceptId(claimedId, name),
                        displayName = name,
                        amount = if (ingredient.isNull("amount")) {
                            null
                        } else {
                            ingredient.getDouble("amount").takeIf { it.isFinite() && it > 0.0 }
                        },
                        unit = ingredient.optStringOrNull("unit"),
                        optional = ingredient.optBoolean("optional", false),
                    ),
                )
            }
        }
        if (ingredients.isEmpty()) return null
        val stepsArray = entry.getJSONArray("steps")
        val steps = List(stepsArray.length()) { stepsArray.getString(it).trim() }
            .filter(String::isNotEmpty)
        if (steps.isEmpty()) return null
        Recipe(
            id = idPrefix + java.util.UUID.randomUUID().toString(),
            title = entry.getString("title").trim(),
            ingredients = ingredients,
            steps = steps,
            servings = entry.optInt("servings", 2).coerceAtLeast(1),
            prepMinutes = entry.optInt("prepMinutes", 30).coerceAtLeast(1),
            tags = entry.optJSONArray("tags")?.let { tags ->
                List(tags.length()) { tags.optString(it) }.filter(String::isNotBlank)
            }.orEmpty(),
            imageRef = null,
            source = source,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }.getOrNull()

    /** A photo-style prompt for [com.example.groceriesapp.data.network.OpenAiImageClient]. */
    fun imagePrompt(recipe: Recipe): String {
        val mains = recipe.ingredients.filter { !it.optional }.take(5)
            .joinToString(", ") { it.displayName }
        return "Appetizing overhead food photograph of ${recipe.title}, homemade, featuring " +
            "$mains, served in simple tableware on a neutral kitchen table, soft natural light, " +
            "shallow depth of field, no text, no people."
    }

    private fun resolveConceptId(claimedId: String?, name: String): String? {
        claimedId?.let { id ->
            FoodConceptCatalog.byId(id)?.let { return it.id }
        }
        FoodConceptCatalog.match(name)?.let { return it.id }
        return FoodConceptCatalog.all.firstOrNull {
            it.displayName.equals(name.trim(), ignoreCase = true)
        }?.id
    }

    private fun conceptCatalogLines(): String =
        FoodConceptCatalog.all.joinToString("\n") { "${it.id} — ${it.displayName}" }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotBlank)
}
