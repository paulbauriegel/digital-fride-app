package com.example.groceriesapp.data.recipes

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Where a recipe came from; drives badges, editability, and image fallbacks. */
enum class RecipeSource {
    CURATED,
    AI,
    USER,
}

data class RecipeIngredient(
    /** Join key into [com.example.groceriesapp.data.taxonomy.FoodConceptCatalog], or null if unmapped. */
    val foodConceptId: String?,
    val displayName: String,
    /** Null means "to taste" — the ingredient matches on presence alone. */
    val amount: Double? = null,
    val unit: String? = null,
    val optional: Boolean = false,
) {
    init {
        require(displayName.isNotBlank()) { "Ingredient name must not be blank." }
        require(amount == null || (amount.isFinite() && amount > 0.0)) {
            "Ingredient amount must be finite and positive."
        }
    }
}

data class Recipe(
    val id: String,
    val title: String,
    val ingredients: List<RecipeIngredient>,
    val steps: List<String>,
    val servings: Int = 2,
    val prepMinutes: Int,
    val tags: List<String> = emptyList(),
    /**
     * `asset:<path under assets/>` for bundled photos,
     * `file:<name under filesDir/groceries/recipes/images/>` for user/AI images, null for none.
     */
    val imageRef: String? = null,
    val source: RecipeSource = RecipeSource.CURATED,
    val createdAtEpochMillis: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Recipe ID must not be blank." }
        require(title.isNotBlank()) { "Recipe title must not be blank." }
        require(servings > 0) { "Servings must be positive." }
        require(prepMinutes > 0) { "Prep minutes must be positive." }
    }
}

fun newRecipeId(): String = UUID.randomUUID().toString()

internal fun RecipeIngredient.toJson(): JSONObject = JSONObject()
    .put("conceptId", foodConceptId ?: JSONObject.NULL)
    .put("name", displayName)
    .put("amount", amount ?: JSONObject.NULL)
    .put("unit", unit ?: JSONObject.NULL)
    .put("optional", optional)

internal fun Recipe.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("servings", servings)
    .put("prepMinutes", prepMinutes)
    .put("tags", JSONArray(tags))
    .put("image", imageRef ?: JSONObject.NULL)
    .put("source", source.name)
    .put("createdAtEpochMillis", createdAtEpochMillis ?: JSONObject.NULL)
    .put("ingredients", JSONArray().apply { ingredients.forEach { put(it.toJson()) } })
    .put("steps", JSONArray(steps))

/** Tolerant of malformed entries: anything unreadable is skipped rather than failing the load. */
internal fun JSONObject.toRecipeOrNull(
    fallbackSource: RecipeSource = RecipeSource.CURATED,
): Recipe? = runCatching {
    val ingredientsArray = getJSONArray("ingredients")
    val ingredients = buildList {
        for (i in 0 until ingredientsArray.length()) {
            val entry = ingredientsArray.optJSONObject(i) ?: continue
            add(
                RecipeIngredient(
                    foodConceptId = entry.optStringOrNull("conceptId"),
                    displayName = entry.getString("name"),
                    amount = if (entry.isNull("amount")) null else entry.getDouble("amount"),
                    unit = entry.optStringOrNull("unit"),
                    optional = entry.optBoolean("optional", false),
                ),
            )
        }
    }
    val stepsArray = getJSONArray("steps")
    Recipe(
        id = getString("id"),
        title = getString("title"),
        ingredients = ingredients,
        steps = List(stepsArray.length()) { stepsArray.getString(it) },
        servings = optInt("servings", 2),
        prepMinutes = getInt("prepMinutes"),
        tags = optJSONArray("tags")?.let { array ->
            List(array.length()) { array.getString(it) }
        }.orEmpty(),
        imageRef = optStringOrNull("image"),
        source = optStringOrNull("source")
            ?.let { name -> RecipeSource.entries.firstOrNull { it.name == name } }
            ?: fallbackSource,
        createdAtEpochMillis = if (isNull("createdAtEpochMillis")) null else optLong("createdAtEpochMillis"),
    )
}.getOrNull()

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)
