package com.example.groceriesapp.data.recipes

import java.util.UUID
import org.json.JSONObject

/**
 * One time a recipe was cooked. Denormalised like
 * [com.example.groceriesapp.data.ConsumptionEvent]: [recipeTitle] survives the recipe being
 * deleted, so the history stays readable. [rating] is the optional 1–5 star verdict given right
 * after cooking; a recipe's displayed rating is the average over its rated entries.
 */
data class CookEntry(
    val recipeId: String,
    val recipeTitle: String,
    val cookedAtEpochMillis: Long,
    val rating: Int? = null,
    /** The household whose inventory the cook consumed from; null when unknown. */
    val householdId: String? = null,
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(id.isNotBlank()) { "Cook entry id must not be blank." }
        require(recipeId.isNotBlank()) { "Cook entry recipe id must not be blank." }
        require(rating == null || rating in MIN_RATING..MAX_RATING) {
            "Rating must be between $MIN_RATING and $MAX_RATING."
        }
    }

    companion object {
        const val MIN_RATING = 1
        const val MAX_RATING = 5
    }
}

internal fun CookEntry.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("recipeId", recipeId)
    .put("recipeTitle", recipeTitle)
    .put("cookedAtEpochMillis", cookedAtEpochMillis)
    .put("rating", rating ?: JSONObject.NULL)
    .put("householdId", householdId ?: JSONObject.NULL)

/** Tolerant: anything unreadable is skipped rather than failing the whole load. */
internal fun JSONObject.toCookEntryOrNull(): CookEntry? = runCatching {
    CookEntry(
        id = getString("id"),
        recipeId = getString("recipeId"),
        recipeTitle = optString("recipeTitle", ""),
        cookedAtEpochMillis = getLong("cookedAtEpochMillis"),
        rating = if (!has("rating") || isNull("rating")) null else getInt("rating"),
        householdId = if (!has("householdId") || isNull("householdId")) null else getString("householdId"),
    )
}.getOrNull()
