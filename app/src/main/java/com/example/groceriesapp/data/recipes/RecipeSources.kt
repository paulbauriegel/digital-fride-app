package com.example.groceriesapp.data.recipes

import java.net.URI
import org.json.JSONObject

/** A cooking site known to publish schema.org Recipe markup, so links from it import keyless. */
data class RecipeSourceSite(
    val id: String,
    val name: String,
    val host: String,
    val homeUrl: String,
)

/** One site the user has imported from, for the "Recent sources" list. */
data class RecentSource(
    val host: String,
    val name: String,
    val lastUsedAtEpochMillis: Long,
    val count: Int,
)

object RecipeSources {

    /** Hand-picked, German-first; every site here embeds JSON-LD and needs no API key. */
    val curated: List<RecipeSourceSite> = listOf(
        RecipeSourceSite("chefkoch", "Chefkoch", "chefkoch.de", "https://www.chefkoch.de"),
        RecipeSourceSite("lecker", "Lecker", "lecker.de", "https://www.lecker.de"),
        RecipeSourceSite("essen-und-trinken", "Essen & Trinken", "essen-und-trinken.de", "https://www.essen-und-trinken.de"),
        RecipeSourceSite("eatsmarter", "EAT SMARTER", "eatsmarter.de", "https://eatsmarter.de"),
        RecipeSourceSite("bbcgoodfood", "BBC Good Food", "bbcgoodfood.com", "https://www.bbcgoodfood.com"),
        RecipeSourceSite("allrecipes", "Allrecipes", "allrecipes.com", "https://www.allrecipes.com"),
        RecipeSourceSite("seriouseats", "Serious Eats", "seriouseats.com", "https://www.seriouseats.com"),
        RecipeSourceSite("bonappetit", "Bon Appétit", "bonappetit.com", "https://www.bonappetit.com"),
        RecipeSourceSite("budgetbytes", "Budget Bytes", "budgetbytes.com", "https://www.budgetbytes.com"),
        RecipeSourceSite("smittenkitchen", "Smitten Kitchen", "smittenkitchen.com", "https://smittenkitchen.com"),
    )

    /** The registrable host of [url] with any `www.`/`m.` prefix removed, or null if unparseable. */
    fun hostOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val host = runCatching { URI(withScheme).host }.getOrNull()?.lowercase() ?: return null
        return host.removePrefix("www.").removePrefix("m.").takeIf(String::isNotEmpty)
    }

    fun matching(url: String): RecipeSourceSite? {
        val host = hostOf(url) ?: return null
        return curated.firstOrNull { host == it.host || host.endsWith("." + it.host) }
    }

    /** A human name for a host: the curated site's name, else the bare host. */
    fun displayName(host: String): String =
        curated.firstOrNull { host == it.host || host.endsWith("." + it.host) }?.name ?: host
}

internal fun RecentSource.toJson(): JSONObject = JSONObject()
    .put("host", host)
    .put("name", name)
    .put("lastUsedAtEpochMillis", lastUsedAtEpochMillis)
    .put("count", count)

internal fun JSONObject.toRecentSourceOrNull(): RecentSource? = runCatching {
    RecentSource(
        host = getString("host"),
        name = optString("name", getString("host")),
        lastUsedAtEpochMillis = getLong("lastUsedAtEpochMillis"),
        count = optInt("count", 1).coerceAtLeast(1),
    )
}.getOrNull()
