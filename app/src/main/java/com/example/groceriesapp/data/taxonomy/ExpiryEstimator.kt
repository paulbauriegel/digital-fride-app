package com.example.groceriesapp.data.taxonomy

enum class ExpirySource {
    /** A date read off the packaging. Exact, so it carries no range. */
    PRINTED_LABEL,

    /** A FoodKeeper-derived window for this specific concept. */
    FOOD_CONCEPT_RULE,

    /** A coarse per-category window, used when the concept itself isn't covered. */
    CATEGORY_FALLBACK,
}

data class ExpiryEstimate(
    val expiresAtEpochMillis: Long,
    val rangeEndEpochMillis: Long?,
    val source: ExpirySource,
)

/**
 * Turns a food concept plus a purchase (or opening) date into a conservative "best used within"
 * window. The earliest edge lands in `expiresAtEpochMillis` so existing expiring-soon logic keeps
 * working; the upper edge is kept separately so the UI can show a range rather than a fake exact
 * date.
 */
class ExpiryEstimator(private val catalog: ShelfLifeCatalog) {

    fun estimate(
        foodConceptId: String?,
        categoryStorageValue: String?,
        nameHint: String? = null,
        purchasedAtEpochMillis: Long?,
        openedAtEpochMillis: Long? = null,
        printedDateEpochMillis: Long? = null,
    ): ExpiryEstimate? {
        if (printedDateEpochMillis != null) {
            return ExpiryEstimate(printedDateEpochMillis, null, ExpirySource.PRINTED_LABEL)
        }

        val category = GroceryCategory.fromStorageValue(categoryStorageValue, nameHint)
        if (!category.supportsExpiry) return null

        val opened = openedAtEpochMillis != null
        val fromConcept = catalog.conceptRule(foodConceptId)?.let { storageWindow(it, opened) }
        val (window, source) = when {
            fromConcept != null -> fromConcept to ExpirySource.FOOD_CONCEPT_RULE
            else -> {
                val fallback = catalog.categoryRule(category)?.let { storageWindow(it, opened) }
                    ?: return null
                fallback to ExpirySource.CATEGORY_FALLBACK
            }
        }

        val anchor = openedAtEpochMillis
            ?: purchasedAtEpochMillis
            ?: System.currentTimeMillis()
        val start = anchor + window.first * MILLIS_PER_DAY
        val end = anchor + window.last * MILLIS_PER_DAY
        return ExpiryEstimate(
            expiresAtEpochMillis = start,
            rangeEndEpochMillis = end.takeIf { it != start },
            source = source,
        )
    }

    /**
     * The window for an item kept at normal storage temperature. Freezer times are deliberately
     * excluded: nothing records that an item was frozen, and a freezer figure would badly
     * overstate how long something like fresh milk keeps.
     */
    private fun storageWindow(rule: ShelfLifeRule, opened: Boolean): IntRange? = when {
        opened -> rule.openedFridgeDays ?: rule.unopenedFridgeDays ?: rule.pantryDays
        else -> rule.unopenedFridgeDays ?: rule.pantryDays
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
