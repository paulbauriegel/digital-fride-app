package com.example.groceriesapp.data.recipes

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A user-named group of recipes ("Weeknight dinners", "High protein"); order is insertion order. */
data class RecipeCollection(
    val name: String,
    val recipeIds: List<String> = emptyList(),
    val createdAtEpochMillis: Long,
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(id.isNotBlank()) { "Collection id must not be blank." }
        require(name.isNotBlank()) { "Collection name must not be blank." }
    }
}

internal fun RecipeCollection.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("createdAtEpochMillis", createdAtEpochMillis)
    .put("recipeIds", JSONArray(recipeIds))

internal fun JSONObject.toRecipeCollectionOrNull(): RecipeCollection? = runCatching {
    val ids = optJSONArray("recipeIds")
    RecipeCollection(
        id = getString("id"),
        name = getString("name"),
        createdAtEpochMillis = optLong("createdAtEpochMillis", 0L),
        recipeIds = ids?.let { array ->
            List(array.length()) { array.getString(it) }.filter(String::isNotBlank).distinct()
        }.orEmpty(),
    )
}.getOrNull()
