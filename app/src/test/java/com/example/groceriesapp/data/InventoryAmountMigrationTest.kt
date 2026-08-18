package com.example.groceriesapp.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `fillLevel` → `packageSize` migration, which runs on the JSON read path and rewrites stored
 * quantities on a real device's real inventory. It is the one part of this change that cannot be
 * undone by tapping something back, so the cases below pin down exactly what it touches — and,
 * just as importantly, what it leaves alone.
 */
class InventoryAmountMigrationTest {

    private fun legacyJson(
        quantity: Double,
        unit: String?,
        fillLevel: String?,
    ): JSONObject = JSONObject()
        .put("id", "legacy-1")
        .put("name", "Frische Milch")
        .put("category", "DAIRY")
        .put("quantity", quantity)
        .put("addedAtEpochMillis", 1_700_000_000_000L)
        .also { json ->
            unit?.let { json.put("unit", it) }
            fillLevel?.let { json.put("fillLevel", it) }
        }

    @Test
    fun halfOfALitreBecomesHalfALitreLeftOfAFullOne() {
        val restored = legacyJson(1.0, "l", "HALF").toInventoryItem()

        assertEquals(0.5, restored.quantity, 1e-9)
        assertEquals(1.0, restored.packageSize!!, 1e-9)
        assertEquals(0.5f, restored.fillFraction!!, 1e-6f)
    }

    @Test
    fun aFullItemKeepsItsQuantityAndGainsAScale() {
        val restored = legacyJson(1.0, "l", "FULL").toInventoryItem()

        assertEquals(1.0, restored.quantity, 1e-9)
        assertEquals(1.0, restored.packageSize!!, 1e-9)
    }

    @Test
    fun lowAndEmptyMapToTheFractionsTheRingUsedToDraw() {
        assertEquals(0.25, legacyJson(2.0, "kg", "LOW").toInventoryItem().fillFraction!!.toDouble(), 1e-6)
        // EMPTY stays a hair above zero rather than becoming a measured zero, because
        // InventoryItem requires a positive quantity.
        assertEquals(0.1, legacyJson(2.0, "kg", "EMPTY").toInventoryItem().quantity, 1e-9)
    }

    @Test
    fun aRowWithNoFillLevelStaysUntracked() {
        val restored = legacyJson(1.0, "l", fillLevel = null).toInventoryItem()

        // Not 1.0. Back-filling these to "full" would put a fill indicator on every row in the
        // list overnight, because the old ring drew nothing for a null fill level.
        assertNull(restored.packageSize)
        assertNull(restored.fillFraction)
    }

    @Test
    fun countsAreNeverScaled() {
        val restored = legacyJson(3.0, "pc", "HALF").toInventoryItem()

        // Halving three yogurts into 1.5 would invent a fractional object.
        assertEquals(3.0, restored.quantity, 1e-9)
        assertEquals(3.0, restored.packageSize!!, 1e-9)
    }

    @Test
    fun germanCountUnitsFromReweAreAlsoTreatedAsCounts() {
        // ReweReceiptParser can persist a raw "Stk"; COUNTABLE_UNITS covers it.
        assertEquals(2.0, legacyJson(2.0, "Stk", "LOW").toInventoryItem().quantity, 1e-9)
    }

    @Test
    fun theMigrationIsIdempotent() {
        val once = legacyJson(1.0, "l", "HALF").toInventoryItem()
        val twice = once.toJson().toInventoryItem()

        assertEquals(once, twice)
        assertEquals(0.5, twice.quantity, 1e-9)
    }

    @Test
    fun aStoredPackageSizeWinsOverALingeringLegacyKey() {
        // Belt and braces: a file written by a mixed-version device must not be re-scaled.
        val json = legacyJson(0.5, "l", "HALF").put("packageSize", 1.0)

        val restored = json.toInventoryItem()

        assertEquals(0.5, restored.quantity, 1e-9)
        assertEquals(1.0, restored.packageSize!!, 1e-9)
    }

    @Test
    fun aRoundTripPreservesTheAmountFields() {
        val item = InventoryItem(
            name = "Penne Rigate",
            quantity = 0.35,
            unit = "kg",
            packageSize = 0.5,
        )

        assertEquals(item, item.toJson().toInventoryItem())
    }
}
