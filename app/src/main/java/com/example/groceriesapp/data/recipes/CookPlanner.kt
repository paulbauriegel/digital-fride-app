package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.FillLevel
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.isCountable

/** One inventory mutation the cook flow proposes; the user can edit before applying. */
sealed interface CookConsumption {
    val itemId: String

    /** Subtract [amount] in the item's own unit; reaching zero removes the item. */
    data class Decrement(override val itemId: String, val amount: Double) : CookConsumption

    /** Coarse "used some" for amounts we can't subtract numerically. */
    data class StepFillLevel(override val itemId: String, val newLevel: FillLevel) : CookConsumption

    data class RemoveAll(override val itemId: String) : CookConsumption
}

/** What applying one [CookConsumption] does to its inventory row. */
sealed interface CookOutcome {
    data class UpdateQuantity(val newQuantity: Double) : CookOutcome
    data class SetFillLevel(val newLevel: FillLevel) : CookOutcome
    data object Remove : CookOutcome
}

/**
 * Proposes a sensible default inventory consumption for a cooked recipe. Pure logic: quantities
 * are subtracted only when the recipe amount and the inventory row share a unit kind; everything
 * else steps the coarse fill level so we never invent numbers.
 */
object CookPlanner {

    /** Deterministic per-item arithmetic for the cook flow; the repository applies the result. */
    fun applyConsumption(item: InventoryItem, consumption: CookConsumption): CookOutcome =
        when (consumption) {
            is CookConsumption.Decrement -> {
                val remaining = item.quantity - consumption.amount
                if (remaining <= QUANTITY_EPSILON) {
                    CookOutcome.Remove
                } else {
                    CookOutcome.UpdateQuantity(remaining)
                }
            }
            is CookConsumption.StepFillLevel ->
                if (consumption.newLevel == FillLevel.EMPTY) {
                    CookOutcome.Remove
                } else {
                    CookOutcome.SetFillLevel(consumption.newLevel)
                }
            is CookConsumption.RemoveAll -> CookOutcome.Remove
        }

    private const val QUANTITY_EPSILON = 1e-9

    fun defaultPlan(match: RecipeMatch): List<CookConsumption> = buildList {
        for (ingredientMatch in match.ingredientMatches) {
            if (ingredientMatch.status == IngredientStatus.MISSING) continue
            addAll(planFor(ingredientMatch))
        }
    }

    private fun planFor(match: IngredientMatch): List<CookConsumption> {
        val needed = UnitNormalizer.normalize(match.ingredient.amount, match.ingredient.unit)
        // Items arrive earliest-expiry-first from the matcher, so expiring stock is used first.
        if (needed == null || needed.kind == UnitKind.UNKNOWN) {
            return match.matchedItems.take(1).map(::stepDown)
        }
        val plan = mutableListOf<CookConsumption>()
        var remaining = needed.value
        for (item in match.matchedItems) {
            if (remaining <= 0.0) break
            val itemNorm = UnitNormalizer.normalize(item.quantity, item.unit)
            if (itemNorm == null || itemNorm.kind != needed.kind) {
                // Present but not numerically comparable: mark it used and consider the need met.
                plan += stepDown(item)
                remaining = 0.0
                continue
            }
            val takeNormalized = remaining.coerceAtMost(itemNorm.value)
            // Convert back into the item's own unit (e.g. 500 normalized grams on a "0.5 kg" row).
            val takeInItemUnits = item.quantity * (takeNormalized / itemNorm.value)
            plan += CookConsumption.Decrement(item.id, takeInItemUnits)
            remaining -= takeNormalized
        }
        return plan
    }

    private fun stepDown(item: InventoryItem): CookConsumption {
        if (item.isCountable && item.quantity > 1.0) {
            return CookConsumption.Decrement(item.id, 1.0)
        }
        val current = item.fillLevel?.let { name -> FillLevel.entries.firstOrNull { it.name == name } }
        return when (current) {
            null -> CookConsumption.StepFillLevel(item.id, FillLevel.HALF)
            FillLevel.FULL -> CookConsumption.StepFillLevel(item.id, FillLevel.HALF)
            FillLevel.HALF -> CookConsumption.StepFillLevel(item.id, FillLevel.LOW)
            FillLevel.LOW, FillLevel.EMPTY -> CookConsumption.RemoveAll(item.id)
        }
    }
}
