package com.example.groceriesapp.data.taxonomy

/** Storage windows in whole days. A null window means the source has no usable figure. */
data class ShelfLifeRule(
    val unopenedFridgeDays: IntRange? = null,
    val openedFridgeDays: IntRange? = null,
    val pantryDays: IntRange? = null,
    val freezerDays: IntRange? = null,
) {
    val isEmpty: Boolean
        get() = unopenedFridgeDays == null &&
            openedFridgeDays == null &&
            pantryDays == null &&
            freezerDays == null
}
