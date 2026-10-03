package com.example.groceriesapp.data.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeTaxonomyTest {

    private fun recipe(tags: List<String>, steps: Int = 3): Recipe = Recipe(
        id = "r",
        title = "r",
        ingredients = listOf(RecipeIngredient("EGGS", "Eggs", 2.0, "pc")),
        steps = List(steps) { "Step $it" },
        prepMinutes = 20,
        tags = tags,
    )

    @Test
    fun `normalizes case spaces and underscores`() {
        assertEquals("high-protein", RecipeTaxonomy.normalizeTag("  High Protein "))
        assertEquals("gluten-free", RecipeTaxonomy.normalizeTag("gluten_free"))
        assertEquals("low-carb", RecipeTaxonomy.normalizeTag("Low--Carb"))
    }

    @Test
    fun `category comes from explicit tags in any spelling`() {
        assertEquals(RecipeCategory.BREAKFAST, RecipeTaxonomy.categoryOf(recipe(listOf("Breakfast"))))
        assertEquals(RecipeCategory.BREAKFAST, RecipeTaxonomy.categoryOf(recipe(listOf("frühstück"))))
        assertEquals(RecipeCategory.LUNCH, RecipeTaxonomy.categoryOf(recipe(listOf("vegetarian", "soup"))))
        assertEquals(RecipeCategory.DESSERT, RecipeTaxonomy.categoryOf(recipe(listOf("Kuchen"))))
        assertEquals(RecipeCategory.DRINK, RecipeTaxonomy.categoryOf(recipe(listOf("smoothie"))))
        assertEquals(RecipeCategory.SNACK, RecipeTaxonomy.categoryOf(recipe(listOf("dip"))))
        assertEquals(RecipeCategory.DINNER, RecipeTaxonomy.categoryOf(recipe(listOf("Hauptgericht"))))
    }

    @Test
    fun `breakfast beats dessert when both are tagged`() {
        assertEquals(RecipeCategory.BREAKFAST, RecipeTaxonomy.categoryOf(recipe(listOf("cake", "breakfast"))))
    }

    @Test
    fun `untagged recipes default by how much cooking they involve`() {
        assertEquals(RecipeCategory.DINNER, RecipeTaxonomy.categoryOf(recipe(listOf("vegetarian", "oven"), steps = 4)))
        assertEquals(RecipeCategory.OTHER, RecipeTaxonomy.categoryOf(recipe(emptyList(), steps = 1)))
    }

    @Test
    fun `dietary tags resolve and vegan implies vegetarian`() {
        assertEquals(setOf(DietaryTag.VEGETARIAN), RecipeTaxonomy.dietaryOf(recipe(listOf("Vegetarisch"))))
        assertEquals(
            setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN),
            RecipeTaxonomy.dietaryOf(recipe(listOf("vegan"))),
        )
        assertEquals(
            setOf(DietaryTag.GLUTEN_FREE, DietaryTag.HIGH_PROTEIN, DietaryTag.LOW_CARB, DietaryTag.DAIRY_FREE),
            RecipeTaxonomy.dietaryOf(recipe(listOf("glutenfrei", "High Protein", "keto", "lactose-free"))),
        )
        assertTrue(RecipeTaxonomy.dietaryOf(recipe(listOf("quick"))).isEmpty())
    }

    @Test
    fun `cook time buckets split at 15 30 and 60`() {
        assertEquals(CookTimeBucket.UNDER_15, RecipeTaxonomy.bucketOf(14))
        assertEquals(CookTimeBucket.FROM_15_TO_30, RecipeTaxonomy.bucketOf(15))
        assertEquals(CookTimeBucket.FROM_15_TO_30, RecipeTaxonomy.bucketOf(30))
        assertEquals(CookTimeBucket.FROM_30_TO_60, RecipeTaxonomy.bucketOf(31))
        assertEquals(CookTimeBucket.FROM_30_TO_60, RecipeTaxonomy.bucketOf(60))
        assertEquals(CookTimeBucket.OVER_60, RecipeTaxonomy.bucketOf(61))
        assertTrue(CookTimeBucket.OVER_60.contains(90))
    }

    @Test
    fun `free tags exclude category and diet vocabulary`() {
        assertEquals(
            listOf("quick", "oven"),
            RecipeTaxonomy.freeTagsOf(recipe(listOf("Quick", "vegetarian", "oven", "breakfast", "quick"))),
        )
    }
}
