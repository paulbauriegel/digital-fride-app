package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Integrity checks over the shipped catalogue, guarding against authoring typos. */
class RecipeCatalogAssetTest {

    private val recipes: List<Recipe> by lazy {
        RecipeCatalog.parse(File("src/main/assets/recipes/catalog.json").readText())
    }

    @Test
    fun `catalog ships at least 30 recipes`() {
        assertTrue("Only ${recipes.size} recipes parsed", recipes.size >= 30)
    }

    @Test
    fun `recipe ids are unique`() {
        val duplicates = recipes.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("Duplicate recipe ids: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `every concept id resolves against the food concept catalog`() {
        val unresolved = recipes.flatMap { recipe ->
            recipe.ingredients
                .mapNotNull { it.foodConceptId }
                .filter { FoodConceptCatalog.byId(it) == null }
                .map { "${recipe.id}: $it" }
        }
        assertTrue("Unknown concept ids: $unresolved", unresolved.isEmpty())
    }

    @Test
    fun `every recipe has steps and at least one required ingredient`() {
        recipes.forEach { recipe ->
            assertTrue("${recipe.id} has no steps", recipe.steps.isNotEmpty())
            assertTrue(
                "${recipe.id} has no required ingredients",
                recipe.ingredients.any { !it.optional },
            )
        }
    }

    @Test
    fun `all catalog recipes are curated`() {
        recipes.forEach { assertEquals(RecipeSource.CURATED, it.source) }
    }
}
