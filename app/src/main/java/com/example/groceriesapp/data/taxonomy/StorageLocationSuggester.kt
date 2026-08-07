package com.example.groceriesapp.data.taxonomy

/**
 * Suggests where a grocery item is normally kept. The baseline is an explicit per-category map;
 * a concept's own [ShelfLifeRule] refines it only in the one unambiguous case (a rule with pantry
 * figures and no fridge figures describes a shelf-stable product, e.g. canned coconut milk filed
 * under DAIRY).
 *
 * FREEZER is never inferred from rule shape: FoodKeeper lists freezer durations even for fresh
 * foods like milk, so a freezer figure says an item *can* be frozen, not that it *is*.
 */
object StorageLocationSuggester {

    private val CATEGORY_DEFAULTS: Map<GroceryCategory, StorageLocation> = mapOf(
        GroceryCategory.FRUIT to StorageLocation.FRIDGE,
        GroceryCategory.VEGETABLES to StorageLocation.FRIDGE,
        GroceryCategory.DAIRY to StorageLocation.FRIDGE,
        GroceryCategory.EGGS to StorageLocation.FRIDGE,
        GroceryCategory.MEAT to StorageLocation.FRIDGE,
        GroceryCategory.FISH_SEAFOOD to StorageLocation.FRIDGE,
        GroceryCategory.FROZEN to StorageLocation.FREEZER,
        GroceryCategory.BAKERY to StorageLocation.PANTRY,
        GroceryCategory.GRAINS_PASTA_RICE to StorageLocation.PANTRY,
        GroceryCategory.BREAKFAST_CEREAL to StorageLocation.PANTRY,
        GroceryCategory.DRINKS to StorageLocation.PANTRY,
        GroceryCategory.SNACKS_SWEETS to StorageLocation.PANTRY,
        GroceryCategory.CONDIMENTS_SAUCES to StorageLocation.PANTRY,
        GroceryCategory.CANNED_JARRED to StorageLocation.PANTRY,
        GroceryCategory.SPICES_BAKING to StorageLocation.PANTRY,
        GroceryCategory.PANTRY to StorageLocation.PANTRY,
        GroceryCategory.HOUSEHOLD to StorageLocation.PANTRY,
        GroceryCategory.OTHER to StorageLocation.PANTRY,
    )

    fun suggest(category: GroceryCategory, conceptRule: ShelfLifeRule? = null): StorageLocation {
        val default = CATEGORY_DEFAULTS.getValue(category)
        if (default == StorageLocation.FRIDGE && conceptRule != null &&
            conceptRule.pantryDays != null &&
            conceptRule.unopenedFridgeDays == null && conceptRule.openedFridgeDays == null
        ) {
            return StorageLocation.PANTRY
        }
        return default
    }
}
