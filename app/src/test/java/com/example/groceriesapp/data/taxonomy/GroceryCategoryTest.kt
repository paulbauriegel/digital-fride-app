package com.example.groceriesapp.data.taxonomy

import org.junit.Assert.assertEquals
import org.junit.Test

class GroceryCategoryTest {

    @Test
    fun readsCanonicalNames() {
        GroceryCategory.entries.forEach { category ->
            assertEquals(category, GroceryCategory.fromStorageValue(category.name))
        }
    }

    /** Categories written by app versions that persisted display strings must still resolve. */
    @Test
    fun readsLegacyDisplayStrings() {
        assertEquals(GroceryCategory.FRUIT, GroceryCategory.fromStorageValue("Fruit"))
        assertEquals(GroceryCategory.VEGETABLES, GroceryCategory.fromStorageValue("Vegetables"))
        assertEquals(GroceryCategory.DAIRY, GroceryCategory.fromStorageValue("Dairy"))
        assertEquals(GroceryCategory.BAKERY, GroceryCategory.fromStorageValue("Bakery"))
        assertEquals(GroceryCategory.PANTRY, GroceryCategory.fromStorageValue("Pantry"))
        assertEquals(GroceryCategory.DRINKS, GroceryCategory.fromStorageValue("Drinks"))
        assertEquals(GroceryCategory.OTHER, GroceryCategory.fromStorageValue("Other"))
    }

    @Test
    fun splitsLegacyMeatAndFishUsingTheItemName() {
        assertEquals(
            GroceryCategory.FISH_SEAFOOD,
            GroceryCategory.fromStorageValue("Meat & Fish", "Lachsfilet"),
        )
        assertEquals(
            GroceryCategory.MEAT,
            GroceryCategory.fromStorageValue("Meat & Fish", "Hähnchenbrust"),
        )
        // Without a usable hint the merged bucket stays on the meat side.
        assertEquals(GroceryCategory.MEAT, GroceryCategory.fromStorageValue("Meat & Fish"))
        assertEquals(
            GroceryCategory.MEAT,
            GroceryCategory.fromStorageValue("Meat & Fish", "Zzyzx"),
        )
    }

    @Test
    fun fallsBackToNameWhenCategoryIsMissing() {
        assertEquals(GroceryCategory.DAIRY, GroceryCategory.fromStorageValue(null, "Bio Milch"))
        assertEquals(GroceryCategory.OTHER, GroceryCategory.fromStorageValue(null, "Zzyzx"))
        assertEquals(GroceryCategory.OTHER, GroceryCategory.fromStorageValue(null, null))
        assertEquals(GroceryCategory.OTHER, GroceryCategory.fromStorageValue("  ", null))
    }

    @Test
    fun unrecognisedStringsBecomeOther() {
        assertEquals(GroceryCategory.OTHER, GroceryCategory.fromStorageValue("Nonsense"))
    }

    @Test
    fun onlyNonFoodCategoriesOptOutOfExpiry() {
        val withoutExpiry = GroceryCategory.entries.filterNot(GroceryCategory::supportsExpiry)
        assertEquals(listOf(GroceryCategory.HOUSEHOLD, GroceryCategory.OTHER), withoutExpiry)
    }

    /** Loose goods aren't sealed packaging, so they're treated as opened from the moment they're bought. */
    @Test
    fun onlyLooseGoodsOpenOnPurchase() {
        val opensOnPurchase = GroceryCategory.entries.filter(GroceryCategory::opensOnPurchase)
        assertEquals(
            setOf(GroceryCategory.FRUIT, GroceryCategory.VEGETABLES, GroceryCategory.BAKERY),
            opensOnPurchase.toSet(),
        )
    }
}
