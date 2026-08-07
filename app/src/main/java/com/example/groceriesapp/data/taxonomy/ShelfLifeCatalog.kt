package com.example.groceriesapp.data.taxonomy

import android.content.Context
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Shelf-life windows per [FoodConcept], derived from the USDA FoodKeeper dataset (public domain)
 * and bundled as `assets/shelf_life_rules.json`.
 *
 * FoodKeeper covers only part of the catalogue — many entries carry sentinels like "package use-by
 * date" instead of a duration — so [ruleFor] falls back to a coarse per-category window.
 */
class ShelfLifeCatalog(private val conceptRules: Map<String, ShelfLifeRule>) {

    /** The concept's own FoodKeeper-derived rule, if the dataset covers it. */
    fun conceptRule(conceptId: String?): ShelfLifeRule? =
        conceptRules[conceptId]?.takeUnless(ShelfLifeRule::isEmpty)

    fun categoryRule(category: GroceryCategory): ShelfLifeRule? =
        if (category.supportsExpiry) CATEGORY_FALLBACKS[category] else null

    companion object {
        private const val ASSET_NAME = "shelf_life_rules.json"

        fun fromAssets(context: Context): ShelfLifeCatalog {
            val json = runCatching {
                context.applicationContext.assets.open(ASSET_NAME).use { stream ->
                    stream.readBytes().toString(StandardCharsets.UTF_8)
                }
            }.getOrNull()
            return ShelfLifeCatalog(json?.let(::parse).orEmpty())
        }

        fun parse(json: String): Map<String, ShelfLifeRule> {
            val rules = runCatching { JSONObject(json).optJSONObject("rules") }.getOrNull()
                ?: return emptyMap()
            return buildMap {
                rules.keys().forEach { key ->
                    val entry = rules.optJSONObject(key) ?: return@forEach
                    val rule = ShelfLifeRule(
                        unopenedFridgeDays = entry.optDayRange("unopenedFridgeDays"),
                        openedFridgeDays = entry.optDayRange("openedFridgeDays"),
                        pantryDays = entry.optDayRange("pantryDays"),
                        freezerDays = entry.optDayRange("freezerDays"),
                    )
                    if (!rule.isEmpty) put(key, rule)
                }
            }
        }

        /**
         * Coarse per-category windows for concepts FoodKeeper does not usefully cover. Deliberately
         * conservative: these drive a "best used within" hint, not a safety guarantee.
         */
        val CATEGORY_FALLBACKS: Map<GroceryCategory, ShelfLifeRule> = mapOf(
            GroceryCategory.FRUIT to ShelfLifeRule(unopenedFridgeDays = 5..7, pantryDays = 3..5),
            GroceryCategory.VEGETABLES to ShelfLifeRule(unopenedFridgeDays = 5..7),
            GroceryCategory.DAIRY to ShelfLifeRule(
                unopenedFridgeDays = 7..10,
                openedFridgeDays = 3..5,
            ),
            GroceryCategory.EGGS to ShelfLifeRule(unopenedFridgeDays = 21..28),
            GroceryCategory.MEAT to ShelfLifeRule(unopenedFridgeDays = 1..2, freezerDays = 90..180),
            GroceryCategory.FISH_SEAFOOD to ShelfLifeRule(
                unopenedFridgeDays = 1..2,
                freezerDays = 60..90,
            ),
            GroceryCategory.BAKERY to ShelfLifeRule(pantryDays = 2..4),
            GroceryCategory.GRAINS_PASTA_RICE to ShelfLifeRule(pantryDays = 365..730),
            GroceryCategory.BREAKFAST_CEREAL to ShelfLifeRule(pantryDays = 180..365),
            GroceryCategory.FROZEN to ShelfLifeRule(freezerDays = 90..180),
            GroceryCategory.DRINKS to ShelfLifeRule(pantryDays = 180..365),
            GroceryCategory.SNACKS_SWEETS to ShelfLifeRule(pantryDays = 90..180),
            GroceryCategory.CONDIMENTS_SAUCES to ShelfLifeRule(
                pantryDays = 180..365,
                openedFridgeDays = 30..60,
            ),
            GroceryCategory.CANNED_JARRED to ShelfLifeRule(
                pantryDays = 365..730,
                openedFridgeDays = 3..5,
            ),
            GroceryCategory.SPICES_BAKING to ShelfLifeRule(pantryDays = 365..730),
            GroceryCategory.PANTRY to ShelfLifeRule(pantryDays = 180..365),
        )
    }
}

private fun JSONObject.optDayRange(key: String): IntRange? {
    val array = optJSONArray(key) ?: return null
    if (array.length() < 2) return null
    val min = array.optInt(0, -1)
    val max = array.optInt(1, -1)
    if (min < 0 || max < 0) return null
    return min..maxOf(min, max)
}
