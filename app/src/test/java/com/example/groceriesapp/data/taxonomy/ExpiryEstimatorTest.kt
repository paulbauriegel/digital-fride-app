package com.example.groceriesapp.data.taxonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpiryEstimatorTest {

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
    fun printedDateWinsOverEveryRule() {
        val printed = purchased + 42 * day
        val estimate = estimator.estimate(
            foodConceptId = "CHEESE_HARD",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
            printedDateEpochMillis = printed,
        )
        assertEquals(ExpirySource.PRINTED_LABEL, estimate?.source)
        assertEquals(printed, estimate?.expiresAtEpochMillis)
        assertNull("a printed date is exact, so it carries no range", estimate?.rangeEndEpochMillis)
    }

    @Test
    fun conceptRuleBeatsCategoryFallback() {
        val estimate = estimator.estimate(
            foodConceptId = "CHEESE_HARD",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
        )
        assertEquals(ExpirySource.FOOD_CONCEPT_RULE, estimate?.source)
        assertEquals(purchased + 100 * day, estimate?.expiresAtEpochMillis)
        assertEquals(purchased + 120 * day, estimate?.rangeEndEpochMillis)
    }

    @Test
    fun unknownConceptFallsBackToItsCategory() {
        val estimate = estimator.estimate(
            foodConceptId = "NOT_IN_CATALOG",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
        )
        assertEquals(ExpirySource.CATEGORY_FALLBACK, estimate?.source)
        val fallback = ShelfLifeCatalog.CATEGORY_FALLBACKS.getValue(GroceryCategory.DAIRY)
        assertEquals(
            purchased + fallback.unopenedFridgeDays!!.first * day,
            estimate?.expiresAtEpochMillis,
        )
    }

    @Test
    fun openingAnItemShortensTheWindowAndReAnchorsIt() {
        val opened = purchased + 10 * day
        val estimate = estimator.estimate(
            foodConceptId = "CHEESE_HARD",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
            openedAtEpochMillis = opened,
        )
        assertEquals(opened + 21 * day, estimate?.expiresAtEpochMillis)
        assertEquals(opened + 28 * day, estimate?.rangeEndEpochMillis)

        val unopened = estimator.estimate(
            foodConceptId = "CHEESE_HARD",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
        )
        assertTrue(
            "opening must not extend the window",
            estimate!!.expiresAtEpochMillis < unopened!!.expiresAtEpochMillis,
        )
    }

    @Test
    fun nonFoodCategoriesNeverGetAnEstimate() {
        listOf(GroceryCategory.HOUSEHOLD, GroceryCategory.OTHER).forEach { category ->
            assertNull(
                "$category should not expire",
                estimator.estimate(
                    foodConceptId = null,
                    categoryStorageValue = category.name,
                    purchasedAtEpochMillis = purchased,
                ),
            )
        }
    }

    @Test
    fun usesPantryWindowWhenThereIsNoFridgeWindow() {
        val estimate = estimator.estimate(
            foodConceptId = "RICE",
            categoryStorageValue = GroceryCategory.GRAINS_PASTA_RICE.name,
            purchasedAtEpochMillis = purchased,
        )
        assertEquals(purchased + 700 * day, estimate?.expiresAtEpochMillis)
        assertEquals(purchased + 730 * day, estimate?.rangeEndEpochMillis)
    }

    @Test
    fun resolvesCategoryFromLegacyStringsAndNames() {
        val legacy = estimator.estimate(
            foodConceptId = null,
            categoryStorageValue = "Dairy",
            nameHint = "Bio Milch",
            purchasedAtEpochMillis = purchased,
        )
        assertNotNull("legacy category strings must still estimate", legacy)
        assertEquals(ExpirySource.CATEGORY_FALLBACK, legacy?.source)
    }

    /**
     * FoodKeeper lists only a freezer figure for several fresh items (milk says "see the package
     * date" for the fridge). Using 90 freezer days for milk sitting in the fridge would be
     * dangerously wrong, so a freezer-only rule must defer to the category window.
     */
    @Test
    fun freezerOnlyRuleFallsBackToTheCategoryWindow() {
        val freezerOnly = ExpiryEstimator(
            ShelfLifeCatalog(mapOf("WHOLE_MILK" to ShelfLifeRule(freezerDays = 90..90))),
        )
        val estimate = freezerOnly.estimate(
            foodConceptId = "WHOLE_MILK",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
        )
        assertEquals(ExpirySource.CATEGORY_FALLBACK, estimate?.source)
        val dairy = ShelfLifeCatalog.CATEGORY_FALLBACKS.getValue(GroceryCategory.DAIRY)
        assertEquals(
            purchased + dairy.unopenedFridgeDays!!.first * day,
            estimate?.expiresAtEpochMillis,
        )
        assertTrue(
            "must not use the 90-day freezer window",
            estimate!!.expiresAtEpochMillis < purchased + 30 * day,
        )
    }

    @Test
    fun realMilkEstimateStaysWithinAFewWeeks() {
        val catalogFile = listOf(
            java.io.File("src/main/assets/shelf_life_rules.json"),
            java.io.File("app/src/main/assets/shelf_life_rules.json"),
        ).first(java.io.File::isFile)
        val real = ExpiryEstimator(ShelfLifeCatalog(ShelfLifeCatalog.parse(catalogFile.readText())))
        val estimate = real.estimate(
            foodConceptId = "WHOLE_MILK",
            categoryStorageValue = GroceryCategory.DAIRY.name,
            purchasedAtEpochMillis = purchased,
        )
        assertNotNull(estimate)
        val days = (estimate!!.expiresAtEpochMillis - purchased) / day
        assertTrue("fresh milk estimated at $days days", days in 1..21)
    }

    @Test
    fun singleDayWindowHasNoRangeEnd() {
        val single = ExpiryEstimator(
            ShelfLifeCatalog(mapOf("X" to ShelfLifeRule(unopenedFridgeDays = 2..2))),
        ).estimate(
            foodConceptId = "X",
            categoryStorageValue = GroceryCategory.MEAT.name,
            purchasedAtEpochMillis = purchased,
        )
        assertEquals(purchased + 2 * day, single?.expiresAtEpochMillis)
        assertNull(single?.rangeEndEpochMillis)
    }
}
