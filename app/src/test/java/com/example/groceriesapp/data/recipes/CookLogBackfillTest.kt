package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.ConsumptionEvent
import com.example.groceriesapp.data.ConsumptionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookLogBackfillTest {

    private fun event(
        at: Long,
        reason: ConsumptionReason = ConsumptionReason.COOKED,
        recipeId: String? = "r1",
        title: String? = "Soup",
    ) = ConsumptionEvent(
        itemId = "i$at",
        name = "Item",
        reason = reason,
        recipeId = recipeId,
        recipeTitle = title,
        removedAtEpochMillis = at,
    )

    @Test
    fun `events of one cook collapse into one entry and separate cooks stay separate`() {
        val minute = 60_000L
        val entries = CookLogBackfill.fromEvents(
            listOf(
                event(1_000), event(1_200), event(2_000),
                event(1_000 + 10 * minute),
                event(5_000, recipeId = "r2", title = "Curry"),
                event(3_000, reason = ConsumptionReason.THROWN_AWAY),
                event(4_000, recipeId = null),
            ),
            householdId = "h1",
        )

        assertEquals(3, entries.size)
        assertEquals(listOf(1_000 + 10 * minute, 5_000L, 1_000L), entries.map { it.cookedAtEpochMillis })
        assertEquals(setOf("r1", "r2"), entries.map { it.recipeId }.toSet())
        assertEquals("Curry", entries.first { it.recipeId == "r2" }.recipeTitle)
        assertTrue(entries.all { it.householdId == "h1" })
        assertTrue(entries.all { it.rating == null })
    }

    @Test
    fun `missing title falls back to the recipe id and no events give no entries`() {
        assertEquals("r1", CookLogBackfill.fromEvents(listOf(event(1L, title = null)), null).single().recipeTitle)
        assertTrue(CookLogBackfill.fromEvents(emptyList(), null).isEmpty())
        assertNull(CookLogBackfill.fromEvents(listOf(event(1L)), null).single().householdId)
    }
}
