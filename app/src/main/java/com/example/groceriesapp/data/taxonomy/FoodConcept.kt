package com.example.groceriesapp.data.taxonomy

import androidx.annotation.DrawableRes

/**
 * A canonical food concept, sized for icon and shelf-life lookup rather than product master data:
 * no brand, barcode or nutrition here.
 *
 * [id] is persisted as `foodConceptId` on receipt and inventory items, so it must stay stable once
 * shipped. Several concepts may legitimately share an [iconRes].
 *
 * [priorityKeywords] are checked before any concept's plain [keywords], for "carrier" nouns that
 * must win over a same-string flavour/fruit keyword — e.g. "Joghurt Erdbeere" (strawberry yogurt)
 * must resolve to yogurt even though "erdbeere" is a longer substring than "joghurt" and would
 * otherwise win a plain longest-match pass. Usually a subset of [keywords], not a separate list.
 */
data class FoodConcept(
    val id: String,
    val category: GroceryCategory,
    val displayName: String,
    @get:DrawableRes val iconRes: Int,
    val keywords: List<String>,
    val priorityKeywords: List<String> = emptyList(),
)
