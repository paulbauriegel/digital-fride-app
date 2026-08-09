package com.example.groceriesapp.data.recipes

import android.content.Context
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * The curated recipe catalogue bundled as `assets/recipes/catalog.json`. Load-once, read-only;
 * user-created and AI-generated recipes live in
 * [com.example.groceriesapp.data.recipes.RecipesRepository] instead.
 */
object RecipeCatalog {
    private const val ASSET_NAME = "recipes/catalog.json"

    fun fromAssets(context: Context): List<Recipe> {
        val json = runCatching {
            context.applicationContext.assets.open(ASSET_NAME).use { stream ->
                stream.readBytes().toString(StandardCharsets.UTF_8)
            }
        }.getOrNull()
        return json?.let(::parse).orEmpty()
    }

    /** Tolerant like the other loaders: malformed entries are skipped, not fatal. */
    fun parse(json: String): List<Recipe> {
        val recipes = runCatching { JSONObject(json).optJSONArray("recipes") }.getOrNull()
            ?: return emptyList()
        return buildList {
            for (i in 0 until recipes.length()) {
                val entry = recipes.optJSONObject(i) ?: continue
                entry.toRecipeOrNull(fallbackSource = RecipeSource.CURATED)?.let(::add)
            }
        }
    }
}
