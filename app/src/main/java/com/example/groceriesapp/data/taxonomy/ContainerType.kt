package com.example.groceriesapp.data.taxonomy

import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.recipes.UnitKind
import com.example.groceriesapp.data.recipes.UnitNormalizer

/**
 * Which vessel to draw when showing how much of an item is left. Purely a shape hint for the
 * amount gauge — nothing about storage, expiry or classification depends on it, which is why it is
 * inferred rather than stored.
 */
enum class ContainerType(val displayName: String) {
    /** Gable-topped carton: milk, plant drinks, juice. */
    CARTON("Carton"),

    /** Necked bottle: water, beer, wine, oil. */
    BOTTLE("Bottle"),

    /** Sealed pack or bag: pasta, rice, sugar, snacks. */
    PACK("Pack"),

    /** Loose goods gathered in a sack: potatoes, onions, apples. */
    BULK("Loose"),
}

/**
 * Picks a container shape from what the app already knows: the unit tells volume from mass, and the
 * coarse category separates a bottle from a carton and loose produce from a sealed pack.
 *
 * A heuristic on purpose. Getting it wrong costs a slightly odd drawing and nothing else, so it is
 * not worth a per-concept table to maintain alongside the icon artwork — and a `pc` count or an
 * unrecognised unit has no natural vessel at all, so it falls to the neutral [ContainerType.PACK].
 */
fun resolveContainerType(
    categoryStorageValue: String?,
    unit: String?,
    nameHint: String? = null,
): ContainerType {
    val category = GroceryCategory.fromStorageValue(categoryStorageValue, nameHint)
    return when (UnitNormalizer.kindOf(unit)) {
        UnitKind.VOLUME -> if (category in BOTTLED_CATEGORIES) {
            ContainerType.BOTTLE
        } else {
            ContainerType.CARTON
        }
        UnitKind.MASS -> if (category in LOOSE_CATEGORIES) {
            ContainerType.BULK
        } else {
            ContainerType.PACK
        }
        UnitKind.COUNT, UnitKind.UNKNOWN -> ContainerType.PACK
    }
}

fun resolveContainerType(item: InventoryItem): ContainerType =
    resolveContainerType(item.category, item.unit, item.name)

/** Liquids that come necked rather than gable-topped. */
private val BOTTLED_CATEGORIES = setOf(
    GroceryCategory.DRINKS,
    GroceryCategory.CONDIMENTS_SAUCES,
    GroceryCategory.HOUSEHOLD,
)

/** Weighed-out goods that arrive in a bag rather than a sealed pack. */
private val LOOSE_CATEGORIES = setOf(
    GroceryCategory.FRUIT,
    GroceryCategory.VEGETABLES,
)
