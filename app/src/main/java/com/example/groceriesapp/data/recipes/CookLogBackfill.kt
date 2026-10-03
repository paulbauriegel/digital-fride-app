package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.ConsumptionEvent
import com.example.groceriesapp.data.ConsumptionReason

/**
 * Rebuilds cook history from the consumption log for installs that cooked recipes before the
 * cook log existed. One "I cooked this" wrote one [ConsumptionEvent] per consumed item, all
 * stamped within the same instant, so events for the same recipe within [windowMillis] of each
 * other collapse into a single [CookEntry]. Ratings are unknowable and stay null.
 */
object CookLogBackfill {

    const val DEFAULT_WINDOW_MILLIS = 5 * 60 * 1000L

    fun fromEvents(
        events: List<ConsumptionEvent>,
        householdId: String?,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    ): List<CookEntry> {
        val cooked = events
            .filter { it.reason == ConsumptionReason.COOKED && !it.recipeId.isNullOrBlank() }
            .sortedBy { it.removedAtEpochMillis }
        val entries = mutableListOf<CookEntry>()
        cooked.groupBy { it.recipeId!! }.forEach { (recipeId, recipeEvents) ->
            var clusterStart: ConsumptionEvent? = null
            recipeEvents.forEach { event ->
                val start = clusterStart
                if (start == null || event.removedAtEpochMillis - start.removedAtEpochMillis > windowMillis) {
                    entries += CookEntry(
                        recipeId = recipeId,
                        recipeTitle = event.recipeTitle ?: recipeId,
                        cookedAtEpochMillis = event.removedAtEpochMillis,
                        householdId = householdId,
                    )
                    clusterStart = event
                }
            }
        }
        return entries.sortedByDescending { it.cookedAtEpochMillis }
    }
}
