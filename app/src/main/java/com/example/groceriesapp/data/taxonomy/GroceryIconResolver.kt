package com.example.groceriesapp.data.taxonomy

import androidx.annotation.DrawableRes

/**
 * Icons are full-colour vector drawables, so render them with `Image(painterResource(...))` — a
 * tinted `Icon` would flatten the artwork to a single colour.
 */
@DrawableRes
fun resolveIconRes(
    foodConceptId: String?,
    categoryStorageValue: String?,
    nameHint: String? = null,
): Int {
    FoodConceptCatalog.byId(foodConceptId)?.let { return it.iconRes }
    return GroceryCategory.fromStorageValue(categoryStorageValue, nameHint).iconRes
}

fun resolveCategory(categoryStorageValue: String?, nameHint: String? = null): GroceryCategory =
    GroceryCategory.fromStorageValue(categoryStorageValue, nameHint)
