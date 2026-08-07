package com.example.groceriesapp.data.taxonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StorageLocationSuggesterTest {

    @Test
    fun frozenGoesToTheFreezer() {
        assertEquals(
            StorageLocation.FREEZER,
            StorageLocationSuggester.suggest(GroceryCategory.FROZEN),
        )
    }

    @Test
    fun freshCategoriesGoToTheFridge() {
        listOf(
            GroceryCategory.FRUIT,
            GroceryCategory.VEGETABLES,
            GroceryCategory.DAIRY,
            GroceryCategory.EGGS,
            GroceryCategory.MEAT,
            GroceryCategory.FISH_SEAFOOD,
        ).forEach { category ->
            assertEquals(
                "$category should default to the fridge",
                StorageLocation.FRIDGE,
                StorageLocationSuggester.suggest(category),
            )
        }
    }

    @Test
    fun shelfStableCategoriesGoToThePantry() {
        listOf(
            GroceryCategory.CANNED_JARRED,
            GroceryCategory.SPICES_BAKING,
            GroceryCategory.GRAINS_PASTA_RICE,
            GroceryCategory.HOUSEHOLD,
            GroceryCategory.OTHER,
        ).forEach { category ->
            assertEquals(
                "$category should default to the pantry",
                StorageLocation.PANTRY,
                StorageLocationSuggester.suggest(category),
            )
        }
    }

    @Test
    fun pantryOnlyConceptRuleOverridesAFridgeDefault() {
        // Shelf-stable canned coconut milk is filed under DAIRY; its rule shape (pantry figures,
        // no fridge figures) reveals it doesn't actually live in the fridge.
        val shelfStable = ShelfLifeRule(pantryDays = 730..1825)
        assertEquals(
            StorageLocation.PANTRY,
            StorageLocationSuggester.suggest(GroceryCategory.DAIRY, shelfStable),
        )
    }

    @Test
    fun ruleWithFridgeFiguresKeepsTheFridgeDefault() {
        val fresh = ShelfLifeRule(unopenedFridgeDays = 7..10, pantryDays = 1..2)
        assertEquals(
            StorageLocation.FRIDGE,
            StorageLocationSuggester.suggest(GroceryCategory.DAIRY, fresh),
        )
    }

    @Test
    fun freezerOnlyRuleMustNotSuggestTheFreezer() {
        // FoodKeeper lists freezer durations even for fresh foods (milk keeps 90 days frozen);
        // a freezer figure means the item *can* be frozen, not that it lives there.
        val freezable = ShelfLifeRule(freezerDays = 90..90)
        assertEquals(
            StorageLocation.FRIDGE,
            StorageLocationSuggester.suggest(GroceryCategory.DAIRY, freezable),
        )
        assertNotEquals(
            StorageLocation.FREEZER,
            StorageLocationSuggester.suggest(GroceryCategory.PANTRY, freezable),
        )
    }

    @Test
    fun everyCategoryHasADefault() {
        GroceryCategory.entries.forEach { category ->
            // getValue inside suggest() would throw on a missing entry.
            StorageLocationSuggester.suggest(category)
        }
    }
}
