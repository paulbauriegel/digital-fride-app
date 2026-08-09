package com.example.groceriesapp.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConsumptionLogTest {

    @Test
    fun roundTripPreservesAllFields() {
        val event = ConsumptionEvent(
            itemId = "item-1",
            name = "Spinat",
            category = "VEGETABLES",
            foodConceptId = "SPINACH",
            quantity = 200.0,
            unit = "g",
            priceCents = 129,
            fillLevel = FillLevel.LOW.name,
            reason = ConsumptionReason.THROWN_AWAY,
            addedAtEpochMillis = 1_700_000_000_000L,
            expiresAtEpochMillis = 1_700_300_000_000L,
            removedAtEpochMillis = 1_700_400_000_000L,
        )

        val restored = event.toJson().toConsumptionEventOrNull()

        assertEquals(event, restored)
    }

    @Test
    fun minimalEventRoundTrips() {
        val event = ConsumptionEvent(
            itemId = "item-2",
            name = "Milch",
            reason = ConsumptionReason.USED_UP,
            removedAtEpochMillis = 1_700_000_000_000L,
        )

        val restored = event.toJson().toConsumptionEventOrNull()

        assertEquals(event, restored)
        assertNull(restored?.priceCents)
        assertNull(restored?.fillLevel)
    }

    @Test
    fun cookedEventWithRecipeRoundTrips() {
        val event = ConsumptionEvent(
            itemId = "item-3",
            name = "Spinat",
            foodConceptId = "SPINACH",
            quantity = 150.0,
            unit = "g",
            reason = ConsumptionReason.COOKED,
            recipeId = "spinach_feta_pasta",
            recipeTitle = "Spinach & Feta Pasta",
            removedAtEpochMillis = 1_700_000_000_000L,
        )

        val restored = event.toJson().toConsumptionEventOrNull()

        assertEquals(event, restored)
        assertEquals("spinach_feta_pasta", restored?.recipeId)
    }

    @Test
    fun eventWithoutRecipeFieldsStillParses() {
        // Entries written by builds that predate the cooked flow have no recipe keys at all.
        val restored = JSONObject()
            .put("id", "legacy")
            .put("itemId", "item-4")
            .put("name", "Milch")
            .put("reason", ConsumptionReason.USED_UP.name)
            .put("removedAtEpochMillis", 1L)
            .toConsumptionEventOrNull()

        assertEquals("legacy", restored?.id)
        assertNull(restored?.recipeId)
        assertNull(restored?.recipeTitle)
    }

    @Test
    fun malformedEntriesAreSkippedNotFatal() {
        assertNull(JSONObject().toConsumptionEventOrNull())
        assertNull(
            JSONObject()
                .put("id", "x")
                .put("itemId", "y")
                .put("name", "Milch")
                .put("reason", "NOT_A_REAL_REASON")
                .put("removedAtEpochMillis", 1L)
                .toConsumptionEventOrNull(),
        )
        assertNull(
            JSONObject()
                .put("id", "x")
                .put("itemId", "y")
                .put("name", "")
                .put("reason", ConsumptionReason.USED_UP.name)
                .put("removedAtEpochMillis", 1L)
                .toConsumptionEventOrNull(),
        )
    }
}
