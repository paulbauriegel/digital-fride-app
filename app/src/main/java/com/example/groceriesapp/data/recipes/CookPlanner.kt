package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.isCountable

/** One inventory mutation the cook flow proposes; the user can edit before applying. */
sealed interface CookConsumption {
    val itemId: String

    /** Subtract [amount] in the item's own unit; reaching zero removes the item. */
    data class Decrement(override val itemId: String, val amount: Double) : CookConsumption

    /**
     * "Used some" for amounts we can't subtract numerically. Carries the pack size so a row that
     * wasn't tracking its amount can start doing so from the cook flow.
     */
    data class UseSome(
        override val itemId: String,
        val newQuantity: Double,
        val packageSize: Double,
    ) : CookConsumption

    data class RemoveAll(override val itemId: String) : CookConsumption
}

/** What applying one [CookConsumption] does to its inventory row. */
sealed interface CookOutcome {
    data class UpdateQuantity(val newQuantity: Double) : CookOutcome
    data class SetAmount(val newQuantity: Double, val packageSize: Double) : CookOutcome
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
            is CookConsumption.UseSome ->
                if (consumption.newQuantity <= consumption.packageSize * USED_UP_FRACTION) {
                    CookOutcome.Remove
                } else {
                    CookOutcome.SetAmount(consumption.newQuantity, consumption.packageSize)
                }
            is CookConsumption.RemoveAll -> CookOutcome.Remove
        }

    /**
     * Below this share of a pack, "used some" reads as "used up" and the row leaves instead — a
     * backstop for an amount edited down to a residue, not the step ladder's own floor.
     */
    private const val USED_UP_FRACTION = 0.1

    /**
     * The smallest share of a pack the halving ladder will step *to*. A quarter left is the last
     * rung; anything below removes the row instead, exactly where the coarse LOW bucket used to
     * hand over to removal.
     */
    private const val LAST_STEP_FRACTION = 0.25

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

    /**
     * The default "used some" step for an amount we can't subtract numerically: halve what's left.
     *
     * Halving reproduces the coarse ladder this replaced — full → half → a quarter → gone — while
     * generalising to whatever the row actually holds. A row that wasn't tracking its amount
     * starts doing so here, taking its purchased quantity as the pack size, because "half of it"
     * is a claim the cook flow can make honestly and "half of unknown" is not.
     */
    internal fun stepDown(item: InventoryItem): CookConsumption {
        if (item.isCountable && item.quantity > 1.0) {
            return CookConsumption.Decrement(item.id, 1.0)
        }
        val packageSize = item.packageSize ?: item.quantity
        val halved = item.quantity / 2.0
        return if (halved < packageSize * LAST_STEP_FRACTION - QUANTITY_EPSILON) {
            CookConsumption.RemoveAll(item.id)
        } else {
            CookConsumption.UseSome(item.id, halved, packageSize)
        }
    }
}
