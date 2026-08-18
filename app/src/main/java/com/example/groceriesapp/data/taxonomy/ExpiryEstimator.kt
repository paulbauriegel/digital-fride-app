package com.example.groceriesapp.data.taxonomy

enum class ExpirySource {
    /** A date read off the packaging. Exact, so it carries no range. */
    PRINTED_LABEL,

    /** A FoodKeeper-derived window for this specific concept. */
    FOOD_CONCEPT_RULE,

    /** A coarse per-category window, used when the concept itself isn't covered. */
    CATEGORY_FALLBACK,

    /**
     * A date the user set by hand. Sticky: never re-estimated over, the same contract
     * [com.example.groceriesapp.data.LocationSource.USER] has for storage locations. Exact, so it
     * carries no range.
     *
     * The guard lives in the repository's `withEstimatedExpiry`, deliberately not in
     * [ExpiryEstimator.estimate] — `estimate` short-circuits on a printed date before anything
     * else, so guarding inside it would let packaging-date OCR silently beat a user's correction.
     */
    USER,
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
        location: StorageLocation? = null,
        locationChangedAtEpochMillis: Long? = null,
    ): ExpiryEstimate? {
        if (printedDateEpochMillis != null) {
            return ExpiryEstimate(printedDateEpochMillis, null, ExpirySource.PRINTED_LABEL)
        }

        val category = GroceryCategory.fromStorageValue(categoryStorageValue, nameHint)
        if (!category.supportsExpiry) return null

        val opened = openedAtEpochMillis != null
        val fromConcept = catalog.conceptRule(foodConceptId)
            ?.let { storageWindow(it, opened, location) }
        val (window, source) = when {
            fromConcept != null -> fromConcept to ExpirySource.FOOD_CONCEPT_RULE
            else -> {
                val fallback = catalog.categoryRule(category)
                    ?.let { storageWindow(it, opened, location) }
                    ?: frozenFallbackWindow(location)
                    ?: return null
                fallback to ExpirySource.CATEGORY_FALLBACK
            }
        }

        val anchor = listOfNotNull(openedAtEpochMillis, locationChangedAtEpochMillis).maxOrNull()
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
     * The window matching where the item is actually kept. With no known location the fridge
     * chain applies, as before locations existed. Freezer figures are used only when the item is
     * recorded as frozen — a freezer duration on its own would badly overstate how long something
     * like fresh milk keeps.
     */
    private fun storageWindow(
        rule: ShelfLifeRule,
        opened: Boolean,
        location: StorageLocation?,
    ): IntRange? = when (location) {
        StorageLocation.FREEZER -> rule.freezerDays
        StorageLocation.PANTRY -> rule.pantryDays ?: fridgeWindow(rule, opened)
        StorageLocation.FRIDGE, null -> fridgeWindow(rule, opened)
    }

    private fun fridgeWindow(rule: ShelfLifeRule, opened: Boolean): IntRange? = when {
        opened -> rule.openedFridgeDays ?: rule.unopenedFridgeDays ?: rule.pantryDays
        else -> rule.unopenedFridgeDays ?: rule.pantryDays
    }

    /**
     * A frozen item whose concept and category rules both lack freezer figures still keeps far
     * longer than any fridge estimate; borrow the generic FROZEN window rather than giving up.
     */
    private fun frozenFallbackWindow(location: StorageLocation?): IntRange? =
        if (location == StorageLocation.FREEZER) {
            catalog.categoryRule(GroceryCategory.FROZEN)?.freezerDays
        } else {
            null
        }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
