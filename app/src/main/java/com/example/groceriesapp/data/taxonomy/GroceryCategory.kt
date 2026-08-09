package com.example.groceriesapp.data.taxonomy

import androidx.annotation.DrawableRes
import com.example.groceriesapp.R

/**
 * Coarse grouping used for filter chips and overview roll-ups. Per-product precision lives in
 * [FoodConcept] instead, so this stays flat rather than growing sub-buckets.
 *
 * [legacyStorageAliases] carry the display strings written by app versions that persisted
 * categories as ad hoc text; [fromStorageValue] still accepts them.
 */
enum class GroceryCategory(
    val displayName: String,
    @get:DrawableRes val iconRes: Int,
    val emojiFallback: String,
    val legacyStorageAliases: Set<String> = emptySet(),
    val opensOnPurchase: Boolean = false,
) {
    FRUIT("Fruit", R.drawable.ic_category_fruit, "🍎", setOf("Fruit"), opensOnPurchase = true),
    VEGETABLES("Vegetables", R.drawable.ic_category_vegetables, "🥦", setOf("Vegetables"), opensOnPurchase = true),
    DAIRY("Dairy", R.drawable.ic_category_dairy, "🥛", setOf("Dairy")),
    EGGS("Eggs", R.drawable.ic_category_eggs, "🥚"),
    MEAT("Meat", R.drawable.ic_category_meat, "🍖", setOf("Meat & Fish")),
    FISH_SEAFOOD("Fish & Seafood", R.drawable.ic_category_fish_seafood, "🐟"),
    BAKERY("Bakery", R.drawable.ic_category_bakery, "🥖", setOf("Bakery"), opensOnPurchase = true),
    GRAINS_PASTA_RICE("Grains & Pasta", R.drawable.ic_category_grains_pasta_rice, "🍝"),
    BREAKFAST_CEREAL("Breakfast", R.drawable.ic_category_breakfast_cereal, "🥣"),
    FROZEN("Frozen", R.drawable.ic_category_frozen, "❄️"),
    DRINKS("Drinks", R.drawable.ic_category_drinks, "🧃", setOf("Drinks")),
    SNACKS_SWEETS("Snacks & Sweets", R.drawable.ic_category_snacks_sweets, "🍪"),
    CONDIMENTS_SAUCES("Condiments & Sauces", R.drawable.ic_category_condiments_sauces, "🫙"),
    CANNED_JARRED("Canned & Jarred", R.drawable.ic_category_canned_jarred, "🥫"),
    SPICES_BAKING("Spices & Baking", R.drawable.ic_category_spices_baking, "🧂"),
    PANTRY("Pantry", R.drawable.ic_category_pantry, "🧺", setOf("Pantry")),
    HOUSEHOLD("Household", R.drawable.ic_category_household, "🧼"),
    /** Bottle/container deposits (Pfand) — a fee, not a grocery, so it never expires or spoils. */
    DEPOSIT("Deposit", R.drawable.ic_food_pfand, "♻️"),
    OTHER("Other", R.drawable.ic_category_other, "🛒", setOf("Other"));

    /** Expiry estimation is meaningless for these, so no shelf-life rule is ever applied. */
    val supportsExpiry: Boolean
        get() = this != HOUSEHOLD && this != DEPOSIT && this != OTHER

    companion object {
        /**
         * Resolves a persisted `category` string: the canonical [name] form, a legacy display
         * string from an older app version, or null/unrecognised (→ [OTHER]).
         *
         * [nameHint] is the item's product name. Legacy data merged meat and fish into a single
         * "Meat & Fish" bucket, which cannot be split from the category string alone, so the hint
         * is re-matched against the catalogue to route old fish purchases to [FISH_SEAFOOD].
         */
        fun fromStorageValue(raw: String?, nameHint: String? = null): GroceryCategory {
            if (raw.isNullOrBlank()) {
                return nameHint?.let(FoodConceptCatalog::match)?.category ?: OTHER
            }
            entries.firstOrNull { it.name == raw }?.let { return it }
            val legacy = entries.firstOrNull { raw in it.legacyStorageAliases } ?: return OTHER
            if (legacy != MEAT) return legacy
            return nameHint?.let(FoodConceptCatalog::match)?.category ?: legacy
        }
    }
}
