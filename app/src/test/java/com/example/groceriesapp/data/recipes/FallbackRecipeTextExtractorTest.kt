package com.example.groceriesapp.data.recipes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FallbackRecipeTextExtractorTest {

    private val rulesRecipe = Recipe(
        id = "rules",
        title = "From rules",
        ingredients = listOf(RecipeIngredient(null, "Salt")),
        steps = listOf("Season."),
        prepMinutes = 5,
    )
    private val aiRecipe = rulesRecipe.copy(id = "ai", title = "From AI")

    private val rules = RecipeTextExtractor { _, _ -> rulesRecipe }

    private fun extract(primary: RecipeTextExtractor?, timeoutMillis: Long = 1_000L): Recipe? = runBlocking {
        FallbackRecipeTextExtractor(primary, rules, timeoutMillis).extract("text", 0L)
    }

    @Test
    fun `no primary goes straight to rules`() {
        assertEquals(rulesRecipe, extract(primary = null))
    }

    @Test
    fun `primary result wins when it produces a recipe`() {
        assertEquals(aiRecipe, extract(primary = RecipeTextExtractor { _, _ -> aiRecipe }))
    }

    @Test
    fun `primary failure null result and timeout fall back to rules`() {
        assertEquals(rulesRecipe, extract(primary = RecipeTextExtractor { _, _ -> error("boom") }))
        assertEquals(rulesRecipe, extract(primary = RecipeTextExtractor { _, _ -> null }))
        assertEquals(
            rulesRecipe,
            extract(
                primary = RecipeTextExtractor { _, _ ->
                    delay(5_000)
                    aiRecipe
                },
                timeoutMillis = 50L,
            ),
        )
    }

    @Test
    fun `cancellation propagates instead of falling back`() {
        assertThrows(CancellationException::class.java) {
            extract(primary = RecipeTextExtractor { _, _ -> throw CancellationException("cancelled") })
        }
    }
}
