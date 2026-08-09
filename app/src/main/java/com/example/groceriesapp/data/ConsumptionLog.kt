package com.example.groceriesapp.data

import java.util.UUID
import org.json.JSONObject

/** Why an item left the inventory. Plain corrections ("just remove") are deliberately not logged. */
enum class ConsumptionReason {
    USED_UP,
    THROWN_AWAY,

    /** Consumed by cooking a recipe; [ConsumptionEvent.recipeId] names which one. */
    COOKED,
}

/**
 * A snapshot of an inventory item at the moment it was removed. Denormalized on purpose — the
 * inventory entry is gone afterwards, and future insights (waste €/month, "thrown away after
 * expiry") need the item's state as it was, not as the catalogue later reclassifies it.
 */
data class ConsumptionEvent(
    val itemId: String,
    val name: String,
    val category: String? = null,
    val foodConceptId: String? = null,
    val quantity: Double = 1.0,
    val unit: String? = null,
    val priceCents: Int? = null,
    val fillLevel: String? = null,
    val reason: ConsumptionReason,
    /** Set for [ConsumptionReason.COOKED]: which recipe consumed the item. Additive fields —
     * older builds' loaders skip unknown reasons/keys, so no schema version bump. */
    val recipeId: String? = null,
    val recipeTitle: String? = null,
    val addedAtEpochMillis: Long? = null,
    val expiresAtEpochMillis: Long? = null,
    val removedAtEpochMillis: Long,
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(name.isNotBlank()) { "Consumption event name must not be blank." }
        require(id.isNotBlank()) { "Consumption event ID must not be blank." }
    }
}

internal fun ConsumptionEvent.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("itemId", itemId)
    .put("name", name)
    .put("category", category ?: JSONObject.NULL)
    .put("foodConceptId", foodConceptId ?: JSONObject.NULL)
    .put("quantity", quantity)
    .put("unit", unit ?: JSONObject.NULL)
    .put("priceCents", priceCents ?: JSONObject.NULL)
    .put("fillLevel", fillLevel ?: JSONObject.NULL)
    .put("reason", reason.name)
    .put("recipeId", recipeId ?: JSONObject.NULL)
    .put("recipeTitle", recipeTitle ?: JSONObject.NULL)
    .put("addedAtEpochMillis", addedAtEpochMillis ?: JSONObject.NULL)
    .put("expiresAtEpochMillis", expiresAtEpochMillis ?: JSONObject.NULL)
    .put("removedAtEpochMillis", removedAtEpochMillis)

/** Tolerant of malformed entries: anything unreadable is skipped rather than failing the load. */
internal fun JSONObject.toConsumptionEventOrNull(): ConsumptionEvent? = runCatching {
    ConsumptionEvent(
        id = getString("id"),
        itemId = getString("itemId"),
        name = getString("name"),
        category = optStringOrNull("category"),
        foodConceptId = optStringOrNull("foodConceptId"),
        quantity = optDouble("quantity", 1.0),
        unit = optStringOrNull("unit"),
        priceCents = if (isNull("priceCents")) null else optInt("priceCents"),
        fillLevel = optStringOrNull("fillLevel"),
        reason = ConsumptionReason.entries.first { it.name == getString("reason") },
        recipeId = optStringOrNull("recipeId"),
        recipeTitle = optStringOrNull("recipeTitle"),
        addedAtEpochMillis = if (isNull("addedAtEpochMillis")) null else optLong("addedAtEpochMillis"),
        expiresAtEpochMillis = if (isNull("expiresAtEpochMillis")) null else optLong("expiresAtEpochMillis"),
        removedAtEpochMillis = getLong("removedAtEpochMillis"),
    )
}.getOrNull()

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)
