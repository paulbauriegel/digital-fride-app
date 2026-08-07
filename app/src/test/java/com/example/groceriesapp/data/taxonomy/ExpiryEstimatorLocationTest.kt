package com.example.groceriesapp.data.taxonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Location-aware estimation. The original [ExpiryEstimatorTest] passes unmodified, which is the
 * proof that a null location preserves pre-location behavior.
 */
class ExpiryEstimatorLocationTest {

    private val day = 24L * 60L * 60L * 1000L
    private val purchased = 1_700_000_000_000L

    private val catalog = ShelfLifeCatalog(
        mapOf(
            "CHEESE_HARD" to ShelfLifeRule(
                unopenedFridgeDays = 100..120,
                openedFridgeDays = 21..28,
                freezerDays = 180..180,
            ),
            "RICE" to ShelfLifeRule(pantryDays = 700..730),
        ),
    )
    private val estimator = ExpiryEstimator(catalog)

    @Test
    fun freezerLocationUsesTheFreezerWindow() {
        val estimate = estimator.estimate(
            foodConceptId = "CHEESE_HARD",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
            location = StorageLocation.FREEZER,
        )
        assertEquals(ExpirySource.FOOD_CONCEPT_RULE, estimate?.source)
        assertEquals(purchased + 180 * day, estimate?.expiresAtEpochMillis)
    }

    @Test
    fun freezerWindowAnchorsAtTheMoveDate() {
        val movedAt = purchased + 5 * day
        val estimate = estimator.estimate(
            foodConceptId = "CHEESE_HARD",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
            location = StorageLocation.FREEZER,
            locationChangedAtEpochMillis = movedAt,
        )
        assertEquals(movedAt + 180 * day, estimate?.expiresAtEpochMillis)
    }

    @Test
    fun frozenItemWithoutAnyFreezerRuleBorrowsTheGenericFrozenWindow() {
        // DAIRY's category fallback has no freezerDays; the FROZEN fallback (90..180) applies.
        val estimate = estimator.estimate(
            foodConceptId = "NOT_IN_CATALOG",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
            location = StorageLocation.FREEZER,
        )
        val frozen = ShelfLifeCatalog.CATEGORY_FALLBACKS.getValue(GroceryCategory.FROZEN)
        assertEquals(ExpirySource.CATEGORY_FALLBACK, estimate?.source)
        assertEquals(
            purchased + frozen.freezerDays!!.first * day,
            estimate?.expiresAtEpochMillis,
        )
    }

    @Test
    fun pantryLocationPrefersThePantryWindow() {
        val estimate = estimator.estimate(
            foodConceptId = "RICE",
            categoryStorageValue = GroceryCategory.GRAINS_PASTA_RICE.name,
            purchasedAtEpochMillis = purchased,
            location = StorageLocation.PANTRY,
        )
        assertEquals(purchased + 700 * day, estimate?.expiresAtEpochMillis)
    }

    @Test
    fun pantryLocationFallsBackToFridgeFiguresWhenNoPantryWindowExists() {
        // Someone keeps cheese in a cool pantry: no pantryDays on the rule, so the conservative
        // fridge chain applies rather than giving up.
        val estimate = estimator.estimate(
            foodConceptId = "CHEESE_HARD",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
            location = StorageLocation.PANTRY,
        )
        assertEquals(purchased + 100 * day, estimate?.expiresAtEpochMillis)
    }

    @Test
    fun latestOfOpenedAndMovedAnchorsTheWindow() {
        val openedAt = purchased + 2 * day
        val movedAt = purchased + 6 * day
        val estimate = estimator.estimate(
            foodConceptId = "CHEESE_HARD",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
            openedAtEpochMillis = openedAt,
            location = StorageLocation.FRIDGE,
            locationChangedAtEpochMillis = movedAt,
        )
        // Opened, so the 21..28 opened-fridge window applies, anchored at the later move date.
        assertEquals(movedAt + 21 * day, estimate?.expiresAtEpochMillis)
        assertEquals(movedAt + 28 * day, estimate?.rangeEndEpochMillis)
    }

    @Test
    fun printedDateStillWinsRegardlessOfLocation() {
        val printed = purchased + 10 * day
        val estimate = estimator.estimate(
            foodConceptId = "CHEESE_HARD",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
            printedDateEpochMillis = printed,
            location = StorageLocation.FREEZER,
            locationChangedAtEpochMillis = purchased + 3 * day,
        )
        assertEquals(ExpirySource.PRINTED_LABEL, estimate?.source)
        assertEquals(printed, estimate?.expiresAtEpochMillis)
        assertNull(estimate?.rangeEndEpochMillis)
    }
}
