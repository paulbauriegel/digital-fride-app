package com.example.groceriesapp.data

import com.example.groceriesapp.data.taxonomy.StorageLocation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InventoryItemJsonTest {

    @Test
    fun roundTripPreservesStorageFields() {
        val item = InventoryItem(
            name = "Milch 1,5%",
            category = "DAIRY",
            foodConceptId = "MILK",
            quantity = 1.0,
            unit = "l",
            priceCents = 149,
            storageLocation = StorageLocation.FRIDGE.name,
            locationSource = LocationSource.USER.name,
            locationChangedAtEpochMillis = 1_700_000_000_000L,
            fillLevel = FillLevel.HALF.name,
            purchasedAtEpochMillis = 1_699_999_000_000L,
            expiresAtEpochMillis = 1_700_500_000_000L,
            expiryRangeEndEpochMillis = 1_700_600_000_000L,
            expiryEstimateSource = "FOOD_CONCEPT_RULE",
            openedAtEpochMillis = 1_700_100_000_000L,
            receiptId = "receipt-1",
            receiptItemId = "item-1",
        )

        val restored = item.toJson().toInventoryItem()

        assertEquals(item, restored)
    }

    @Test
    fun legacyJsonWithoutStorageFieldsLoadsWithNulls() {
        // Exactly what a schema-3 index.json entry looks like: no storage/fill keys at all.
        val legacy = JSONObject()
            .put("id", "legacy-1")
            .put("name", "Greek Yogurt")
            .put("category", "DAIRY")
            .put("quantity", 1.0)
            .put("addedAtEpochMillis", 1_700_000_000_000L)

        val restored = legacy.toInventoryItem()

        assertNull(restored.storageLocation)
        assertNull(restored.locationSource)
        assertNull(restored.locationChangedAtEpochMillis)
        assertNull(restored.fillLevel)
        assertEquals("Greek Yogurt", restored.name)
    }

    @Test
    fun explicitJsonNullsLoadAsNulls() {
        val item = InventoryItem(name = "Brot")
        val restored = item.toJson().toInventoryItem()
        assertNull(restored.storageLocation)
        assertNull(restored.fillLevel)
    }
}
