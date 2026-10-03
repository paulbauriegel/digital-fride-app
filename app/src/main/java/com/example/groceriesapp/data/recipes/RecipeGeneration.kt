package com.example.groceriesapp.data.recipes

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Dietary / style constraints the user can put on an AI generation request. */
enum class GenerationPreference(val label: String, val promptText: String, val tag: String) {
    VEGETARIAN("Vegetarian", "vegetarian (no meat or fish)", "vegetarian"),
    VEGAN("Vegan", "vegan (no animal products)", "vegan"),
    HIGH_PROTEIN("High protein", "high in protein", "high-protein"),
    LOW_CARB("Low carb", "low in carbohydrates", "low-carb"),
    GLUTEN_FREE("Gluten-free", "gluten-free", "gluten-free"),
    DAIRY_FREE("Dairy-free", "dairy-free", "dairy-free"),
    KID_FRIENDLY("Kid-friendly", "mild and kid-friendly", "kid-friendly"),
    SPICY("Spicy", "spicy", "spicy"),
}

enum class GenerationTime(val label: String, val minMinutes: Int?, val maxMinutes: Int?) {
    ANY("Any", null, null),
    UNDER_30("< 30 min", null, 30),
    FROM_30_TO_60("30 – 60 min", 30, 60),
    OVER_60("> 60 min", 60, null),
    ;

    val promptText: String
        get() = when (this) {
            ANY -> "any cooking time"
            UNDER_30 -> "ready in under 30 minutes"
            FROM_30_TO_60 -> "30 to 60 minutes of cooking time"
            OVER_60 -> "a slow, longer cook of over an hour"
        }
}

/** What the user asked the model for — kept with the result so it can be re-run or reviewed. */
data class RecipeGenerationRequest(
    val selectedInventoryItemIds: Set<String> = emptySet(),
    val preferences: Set<GenerationPreference> = emptySet(),
    val time: GenerationTime = GenerationTime.ANY,
    val freeText: String = "",
    val count: Int = DEFAULT_COUNT,
) {
    init {
        require(count in 1..MAX_COUNT) { "Count must be between 1 and $MAX_COUNT." }
    }

    /** One-line summary for history rows, e.g. "3 ingredients · Vegetarian · < 30 min". */
    fun summary(): String = buildList {
        if (selectedInventoryItemIds.isNotEmpty()) {
            add(
                if (selectedInventoryItemIds.size == 1) "1 ingredient" else "${selectedInventoryItemIds.size} ingredients",
            )
        }
        addAll(preferences.map { it.label })
        if (time != GenerationTime.ANY) add(time.label)
        if (freeText.isNotBlank()) add("“${freeText.trim()}”")
    }.ifEmpty { listOf("From your inventory") }.joinToString(" · ")

    companion object {
        const val DEFAULT_COUNT = 6
        const val MAX_COUNT = 10
    }
}

data class RecipeGenerationResult(
    val request: RecipeGenerationRequest,
    val recipeIds: List<String>,
    val createdAtEpochMillis: Long,
    val id: String = UUID.randomUUID().toString(),
)

/** Tabs on the results screen; each is a view over the same generated batch. */
enum class GenerationResultTab(val label: String) {
    BEST_MATCH("Best match"),
    QUICK_EASY("Quick & easy"),
    HIGH_PROTEIN("High protein"),
}

object GenerationGrouping {
    private val PROTEIN_TAGS = setOf("high-protein", "protein", "proteinreich", "ai:protein")

    /** Every tab gets a list; an empty one means "none of this batch qualifies". */
    fun group(matches: List<RecipeMatch>): Map<GenerationResultTab, List<RecipeMatch>> = mapOf(
        GenerationResultTab.BEST_MATCH to matches.sortedByDescending { it.score },
        GenerationResultTab.QUICK_EASY to matches
            .filter { it.recipe.prepMinutes <= QUICK_MINUTES || "ai:quick" in it.recipe.tags }
            .sortedBy { it.recipe.prepMinutes },
        GenerationResultTab.HIGH_PROTEIN to matches
            .filter { match -> match.recipe.tags.any { RecipeTaxonomy.normalizeTag(it) in PROTEIN_TAGS } }
            .sortedByDescending { it.score },
    )

    /** "Good match" at three quarters coverage, same threshold the matcher uses for best matches. */
    fun isGoodMatch(match: RecipeMatch): Boolean = match.coverage >= GOOD_MATCH_COVERAGE

    private const val QUICK_MINUTES = 30
    private const val GOOD_MATCH_COVERAGE = 0.75
}

internal fun RecipeGenerationRequest.toJson(): JSONObject = JSONObject()
    .put("selectedInventoryItemIds", JSONArray(selectedInventoryItemIds.toList()))
    .put("preferences", JSONArray(preferences.map { it.name }))
    .put("time", time.name)
    .put("freeText", freeText)
    .put("count", count)

internal fun JSONObject.toRecipeGenerationRequestOrNull(): RecipeGenerationRequest? = runCatching {
    fun strings(key: String): List<String> = optJSONArray(key)?.let { array ->
        List(array.length()) { array.getString(it) }
    }.orEmpty()
    RecipeGenerationRequest(
        selectedInventoryItemIds = strings("selectedInventoryItemIds").toSet(),
        preferences = strings("preferences")
            .mapNotNull { name -> GenerationPreference.entries.firstOrNull { it.name == name } }
            .toSet(),
        time = GenerationTime.entries.firstOrNull { it.name == optString("time") } ?: GenerationTime.ANY,
        freeText = optString("freeText", ""),
        count = optInt("count", RecipeGenerationRequest.DEFAULT_COUNT)
            .coerceIn(1, RecipeGenerationRequest.MAX_COUNT),
    )
}.getOrNull()

internal fun RecipeGenerationResult.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("request", request.toJson())
    .put("recipeIds", JSONArray(recipeIds))
    .put("createdAtEpochMillis", createdAtEpochMillis)

internal fun JSONObject.toRecipeGenerationResultOrNull(): RecipeGenerationResult? = runCatching {
    val ids = getJSONArray("recipeIds")
    RecipeGenerationResult(
        id = getString("id"),
        request = getJSONObject("request").toRecipeGenerationRequestOrNull() ?: return null,
        recipeIds = List(ids.length()) { ids.getString(it) },
        createdAtEpochMillis = getLong("createdAtEpochMillis"),
    )
}.getOrNull()
