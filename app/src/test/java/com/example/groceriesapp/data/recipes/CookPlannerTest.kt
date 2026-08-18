package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.InventoryItem
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookPlannerTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 8)
    private val matcher = RecipeMatcher(ZoneId.systemDefault())

    private fun item(
        conceptId: String,
        quantity: Double = 1.0,
        unit: String? = null,
        packageSize: Double? = null,
        id: String,
    ): InventoryItem = InventoryItem(
        name = conceptId,
        foodConceptId = conceptId,
        quantity = quantity,
        unit = unit,
        packageSize = packageSize,
        id = id,
    )

    private fun planFor(ingredients: List<RecipeIngredient>, inventory: List<InventoryItem>): List<CookConsumption> {
        val recipe = Recipe(
            id = "r",
            title = "r",
            ingredients = ingredients,
            steps = listOf("Cook."),
            prepMinutes = 20,
        )
        return CookPlanner.defaultPlan(matcher.matchOne(recipe, inventory, today))
    }

    @Test
    fun `countable need decrements pieces capped at what is there`() {
        val plan = planFor(
            listOf(RecipeIngredient("EGGS", "Eggs", 4.0, "pc")),
            listOf(item("EGGS", quantity = 3.0, unit = "pc", id = "eggs1")),
        )

        assertEquals(listOf(CookConsumption.Decrement("eggs1", 3.0)), plan)
    }

    @Test
    fun `mass need converts into the item's own unit`() {
        val plan = planFor(
            listOf(RecipeIngredient("POTATO", "Potatoes", 500.0, "g")),
            listOf(item("POTATO", quantity = 2.0, unit = "kg", id = "pot1")),
        )

        val decrement = plan.single() as CookConsumption.Decrement
        assertEquals("pot1", decrement.itemId)
        // 500 g of a 2 kg row = 0.5 in the row's own kg unit.
        assertEquals(0.5, decrement.amount, 1e-9)
    }

    @Test
    fun `need spreads across rows earliest expiry first`() {
        val plan = planFor(
            listOf(RecipeIngredient("SPINACH", "Spinach", 250.0, "g")),
            listOf(
                item("SPINACH", quantity = 150.0, unit = "g", id = "a"),
                item("SPINACH", quantity = 200.0, unit = "g", id = "b"),
            ),
        )

        assertEquals(2, plan.size)
        assertEquals(CookConsumption.Decrement("a", 150.0), plan[0])
        assertEquals(CookConsumption.Decrement("b", 100.0), plan[1])
    }

    @Test
    fun `presence-only ingredient halves what is left`() {
        val plan = planFor(
            listOf(RecipeIngredient("OIL", "Olive oil", 2.0, "tbsp")),
            listOf(item("OIL", quantity = 1.0, packageSize = 1.0, id = "oil1")),
        )

        assertEquals(listOf<CookConsumption>(CookConsumption.UseSome("oil1", 0.5, 1.0)), plan)
    }

    @Test
    fun `an untracked row starts tracking at half of what was bought`() {
        val plan = planFor(
            listOf(RecipeIngredient("SOY_SAUCE", "Soy sauce", 3.0, "tbsp")),
            listOf(item("SOY_SAUCE", quantity = 1.0, id = "soy1")),
        )

        assertEquals(listOf<CookConsumption>(CookConsumption.UseSome("soy1", 0.5, 1.0)), plan)
    }

    @Test
    fun `a quarter-full item gets removed entirely rather than stepping again`() {
        // The old coarse ladder ended at LOW -> removal; a quarter of a pack is the last rung.
        val plan = planFor(
            listOf(RecipeIngredient("BUTTER", "Butter", 1.0, "tbsp")),
            listOf(item("BUTTER", quantity = 0.25, packageSize = 1.0, id = "butter1")),
        )

        assertEquals(listOf<CookConsumption>(CookConsumption.RemoveAll("butter1")), plan)
    }

    @Test
    fun `the halving ladder reproduces the coarse buckets it replaced`() {
        fun step(quantity: Double) =
            CookPlanner.stepDown(item("OIL", quantity = quantity, packageSize = 1.0, id = "oil"))

        assertEquals(CookConsumption.UseSome("oil", 0.5, 1.0), step(1.0))
        assertEquals(CookConsumption.UseSome("oil", 0.25, 1.0), step(0.5))
        assertEquals(CookConsumption.RemoveAll("oil"), step(0.25))
    }

    @Test
    fun `missing ingredients contribute nothing to the plan`() {
        val plan = planFor(
            listOf(
                RecipeIngredient("EGGS", "Eggs", 2.0, "pc"),
                RecipeIngredient("SALMON", "Salmon", 300.0, "g"),
            ),
            listOf(item("EGGS", quantity = 6.0, unit = "pc", id = "eggs1")),
        )

        assertEquals(listOf(CookConsumption.Decrement("eggs1", 2.0)), plan)
    }

    @Test
    fun `applyConsumption decrements and keeps the item above zero`() {
        val outcome = CookPlanner.applyConsumption(
            item("EGGS", quantity = 6.0, unit = "pc", id = "eggs"),
            CookConsumption.Decrement("eggs", 2.0),
        )

        assertEquals(CookOutcome.UpdateQuantity(4.0), outcome)
    }

    @Test
    fun `applyConsumption removes the item when the decrement reaches zero`() {
        val outcome = CookPlanner.applyConsumption(
            item("EGGS", quantity = 2.0, unit = "pc", id = "eggs"),
            CookConsumption.Decrement("eggs", 2.0),
        )

        assertEquals(CookOutcome.Remove, outcome)
    }

    @Test
    fun `applyConsumption maps amount steps and normalizes a residue to removal`() {
        val item = item("OIL", quantity = 1.0, id = "oil")

        assertEquals(
            CookOutcome.SetAmount(0.25, 1.0),
            CookPlanner.applyConsumption(item, CookConsumption.UseSome("oil", 0.25, 1.0)),
        )
        assertEquals(
            CookOutcome.Remove,
            CookPlanner.applyConsumption(item, CookConsumption.UseSome("oil", 0.05, 1.0)),
        )
        assertEquals(
            CookOutcome.Remove,
            CookPlanner.applyConsumption(item, CookConsumption.RemoveAll("oil")),
        )
    }

    @Test
    fun `incomparable unit on the matched row falls back to an amount step`() {
        val plan = planFor(
            listOf(RecipeIngredient("CREAM", "Cream", 200.0, "ml")),
            listOf(item("CREAM", quantity = 1.0, unit = "Becher", id = "cream1")),
        )

        assertTrue(plan.single() is CookConsumption.UseSome)
    }
}
