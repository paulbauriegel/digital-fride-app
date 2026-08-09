package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.FillLevel
import com.example.groceriesapp.data.InventoryItem
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeMatcherTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.of(2026, 8, 8)
    private val matcher = RecipeMatcher(zone)

    private fun expiresInDays(days: Long): Long =
        today.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun item(
        conceptId: String?,
        quantity: Double = 1.0,
        unit: String? = null,
        expiresInDays: Long? = null,
        fillLevel: FillLevel? = null,
        name: String = conceptId ?: "item",
    ): InventoryItem = InventoryItem(
        name = name,
        foodConceptId = conceptId,
        quantity = quantity,
        unit = unit,
        expiresAtEpochMillis = expiresInDays?.let(::expiresInDays),
        fillLevel = fillLevel?.name,
    )

    private fun recipe(
        id: String = "r",
        prepMinutes: Int = 20,
        ingredients: List<RecipeIngredient>,
    ): Recipe = Recipe(
        id = id,
        title = id,
        ingredients = ingredients,
        steps = listOf("Cook."),
        prepMinutes = prepMinutes,
    )

    @Test
    fun `full coverage when every required ingredient is present in sufficient quantity`() {
        val match = matcher.matchOne(
            recipe(
                ingredients = listOf(
                    RecipeIngredient("PASTA", "Pasta", 250.0, "g"),
                    RecipeIngredient("TOMATO", "Tomatoes", 2.0, "pc"),
                    RecipeIngredient("CHEESE_HARD", "Parmesan", 30.0, "g", optional = true),
                ),
            ),
            listOf(
                item("PASTA", 500.0, "g"),
                item("TOMATO", 4.0, "pc"),
            ),
            today,
        )

        assertEquals(1.0, match.coverage, 1e-9)
        assertTrue(match.missingRequired.isEmpty())
        // The optional missing parmesan does not drag coverage down.
        assertEquals(
            IngredientStatus.MISSING,
            match.ingredientMatches.first { it.ingredient.displayName == "Parmesan" }.status,
        )
    }

    @Test
    fun `insufficient quantity counts as partial`() {
        val match = matcher.matchOne(
            recipe(
                ingredients = listOf(
                    RecipeIngredient("SPINACH", "Spinach", 200.0, "g"),
                    RecipeIngredient("EGGS", "Eggs", 2.0, "pc"),
                ),
            ),
            listOf(
                item("SPINACH", 100.0, "g"),
                item("EGGS", 6.0, "pc"),
            ),
            today,
        )

        assertEquals(
            IngredientStatus.PARTIAL,
            match.ingredientMatches.first { it.ingredient.foodConceptId == "SPINACH" }.status,
        )
        assertEquals(0.75, match.coverage, 1e-9)
    }

    @Test
    fun `quantities sum across multiple rows of the same concept`() {
        val match = matcher.matchOne(
            recipe(ingredients = listOf(RecipeIngredient("POTATO", "Potatoes", 600.0, "g"))),
            listOf(
                item("POTATO", 400.0, "g"),
                item("POTATO", 0.5, "kg"),
            ),
            today,
        )

        assertEquals(1.0, match.coverage, 1e-9)
    }

    @Test
    fun `unknown units fall back to presence-only matching`() {
        val match = matcher.matchOne(
            recipe(ingredients = listOf(RecipeIngredient("OIL", "Olive oil", 2.0, "tbsp"))),
            listOf(item("OIL", 1.0, "Flasche")),
            today,
        )

        assertEquals(IngredientStatus.HAVE, match.ingredientMatches.single().status)
    }

    @Test
    fun `unit kind mismatch falls back to presence-only matching`() {
        val match = matcher.matchOne(
            recipe(ingredients = listOf(RecipeIngredient("CREAM", "Cream", 200.0, "ml"))),
            listOf(item("CREAM", 200.0, "g")),
            today,
        )

        assertEquals(IngredientStatus.HAVE, match.ingredientMatches.single().status)
    }

    @Test
    fun `empty fill level items are excluded from matching`() {
        val match = matcher.matchOne(
            recipe(ingredients = listOf(RecipeIngredient("BUTTER", "Butter", 20.0, "g"))),
            listOf(item("BUTTER", 250.0, "g", fillLevel = FillLevel.EMPTY)),
            today,
        )

        assertEquals(IngredientStatus.MISSING, match.ingredientMatches.single().status)
        assertEquals(0.0, match.coverage, 1e-9)
    }

    @Test
    fun `expiring ingredients are counted and boost the score`() {
        val ingredients = listOf(
            RecipeIngredient("SPINACH", "Spinach", 100.0, "g"),
            RecipeIngredient("EGGS", "Eggs", 2.0, "pc"),
        )
        val expiringInventory = listOf(
            item("SPINACH", 200.0, "g", expiresInDays = 1),
            item("EGGS", 4.0, "pc", expiresInDays = 2),
        )
        val freshInventory = listOf(
            item("SPINACH", 200.0, "g", expiresInDays = 14),
            item("EGGS", 4.0, "pc", expiresInDays = 14),
        )

        val expiring = matcher.matchOne(recipe(ingredients = ingredients), expiringInventory, today)
        val fresh = matcher.matchOne(recipe(ingredients = ingredients), freshInventory, today)

        assertEquals(2, expiring.expiringUsed)
        assertEquals(0, fresh.expiringUsed)
        assertTrue(expiring.score > fresh.score)
    }

    @Test
    fun `matched items are ordered earliest expiry first`() {
        val match = matcher.matchOne(
            recipe(ingredients = listOf(RecipeIngredient("YOGURT", "Yogurt", 1.0, "pc"))),
            listOf(
                item("YOGURT", 1.0, "pc", expiresInDays = 10, name = "fresh"),
                item("YOGURT", 1.0, "pc", expiresInDays = 2, name = "old"),
                item("YOGURT", 1.0, "pc", name = "unknown expiry"),
            ),
            today,
        )

        assertEquals(
            listOf("old", "fresh", "unknown expiry"),
            match.ingredientMatches.single().matchedItems.map { it.name },
        )
    }

    @Test
    fun `favorite recipes score higher than identical non-favorites`() {
        val ingredients = listOf(RecipeIngredient("RICE", "Rice", 200.0, "g"))
        val inventory = listOf(item("RICE", 1.0, "kg"))

        val plain = matcher.matchOne(recipe(id = "a", ingredients = ingredients), inventory, today)
        val favorite = matcher.matchOne(
            recipe(id = "a", ingredients = ingredients),
            inventory,
            today,
            favoriteIds = setOf("a"),
        )

        assertEquals(0.05, favorite.score - plain.score, 1e-9)
    }

    @Test
    fun `sections split at 75 percent coverage`() {
        val inventory = listOf(item("PASTA", 500.0, "g"), item("TOMATO", 3.0, "pc"))
        val covered = recipe(
            id = "covered",
            ingredients = listOf(
                RecipeIngredient("PASTA", "Pasta", 250.0, "g"),
                RecipeIngredient("TOMATO", "Tomatoes", 2.0, "pc"),
            ),
        )
        val uncovered = recipe(
            id = "uncovered",
            ingredients = listOf(
                RecipeIngredient("SALMON", "Salmon", 300.0, "g"),
                RecipeIngredient("POTATO", "Potatoes", 500.0, "g"),
            ),
        )

        val sections = matcher.sections(listOf(covered, uncovered), inventory, today)

        assertEquals(listOf("covered"), sections.bestMatches.map { it.recipe.id })
        assertEquals(listOf("uncovered"), sections.moreIdeas.map { it.recipe.id })
    }

    @Test
    fun `max prep minutes filter removes slow recipes`() {
        val quick = recipe(id = "quick", prepMinutes = 15, ingredients = listOf(RecipeIngredient("EGGS", "Eggs", 2.0, "pc")))
        val slow = recipe(id = "slow", prepMinutes = 50, ingredients = listOf(RecipeIngredient("EGGS", "Eggs", 2.0, "pc")))

        val sections = matcher.sections(
            listOf(quick, slow),
            emptyList(),
            today,
            RecipeFilters(maxPrepMinutes = 30),
        )

        assertEquals(
            listOf("quick"),
            (sections.bestMatches + sections.moreIdeas).map { it.recipe.id },
        )
    }

    @Test
    fun `only what I have filter drops recipes with missing required ingredients`() {
        val inventory = listOf(item("EGGS", 6.0, "pc"))
        val have = recipe(id = "have", ingredients = listOf(RecipeIngredient("EGGS", "Eggs", 2.0, "pc")))
        val missing = recipe(
            id = "missing",
            ingredients = listOf(
                RecipeIngredient("EGGS", "Eggs", 2.0, "pc"),
                RecipeIngredient("SALMON", "Salmon", 200.0, "g"),
            ),
        )

        val sections = matcher.sections(
            listOf(have, missing),
            inventory,
            today,
            RecipeFilters(onlyWhatIHave = true),
        )

        assertEquals(
            listOf("have"),
            (sections.bestMatches + sections.moreIdeas).map { it.recipe.id },
        )
    }

    @Test
    fun `favorites filter keeps only favorited recipes`() {
        val a = recipe(id = "a", ingredients = listOf(RecipeIngredient("EGGS", "Eggs", 2.0, "pc")))
        val b = recipe(id = "b", ingredients = listOf(RecipeIngredient("EGGS", "Eggs", 2.0, "pc")))

        val sections = matcher.sections(
            listOf(a, b),
            emptyList(),
            today,
            RecipeFilters(favoritesOnly = true),
            favoriteIds = setOf("b"),
        )

        assertEquals(
            listOf("b"),
            (sections.bestMatches + sections.moreIdeas).map { it.recipe.id },
        )
    }

    @Test
    fun `query matches titles and ingredient names`() {
        val soup = recipe(id = "soup", ingredients = listOf(RecipeIngredient("TOMATO", "Tomatoes", 2.0, "pc")))
        val salad = recipe(id = "salad", ingredients = listOf(RecipeIngredient("CUCUMBER", "Cucumber", 1.0, "pc")))

        val byIngredient = matcher.sections(
            listOf(soup, salad),
            emptyList(),
            today,
            RecipeFilters(query = "tomato"),
        )

        assertEquals(
            listOf("soup"),
            (byIngredient.bestMatches + byIngredient.moreIdeas).map { it.recipe.id },
        )
    }
}
