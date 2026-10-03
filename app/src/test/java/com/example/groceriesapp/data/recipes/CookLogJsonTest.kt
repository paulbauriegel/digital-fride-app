package com.example.groceriesapp.data.recipes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CookLogJsonTest {

    @Test
    fun `round trips every field`() {
        val entry = CookEntry(
            id = "e1",
            recipeId = "r1",
            recipeTitle = "Shakshuka",
            cookedAtEpochMillis = 1_700_000_000_000L,
            rating = 4,
            householdId = "h1",
        )

        assertEquals(entry, entry.toJson().toCookEntryOrNull())
    }

    @Test
    fun `missing rating and household read back as null`() {
        val json = JSONObject()
            .put("id", "e2")
            .put("recipeId", "r2")
            .put("cookedAtEpochMillis", 42L)

        val entry = json.toCookEntryOrNull()

        assertEquals("e2", entry?.id)
        assertNull(entry?.rating)
        assertNull(entry?.householdId)
        assertEquals("", entry?.recipeTitle)
    }

    @Test
    fun `json null rating reads back as null`() {
        val entry = CookEntry("r", "t", 1L, rating = null, id = "e").toJson().toCookEntryOrNull()
        assertNull(entry?.rating)
    }

    @Test
    fun `malformed entries are skipped`() {
        assertNull(JSONObject().put("id", "x").toCookEntryOrNull())
        assertNull(JSONObject().put("id", "x").put("recipeId", "r").put("cookedAtEpochMillis", 1L).put("rating", 9).toCookEntryOrNull())
    }

    @Test
    fun `rating outside one to five is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CookEntry(recipeId = "r", recipeTitle = "t", cookedAtEpochMillis = 1L, rating = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CookEntry(recipeId = "r", recipeTitle = "t", cookedAtEpochMillis = 1L, rating = 6)
        }
    }

    @Test
    fun `collections round trip and drop blank ids`() {
        val collection = RecipeCollection(
            id = "c1",
            name = "Weeknight dinners",
            recipeIds = listOf("a", "b"),
            createdAtEpochMillis = 5L,
        )
        assertEquals(collection, collection.toJson().toRecipeCollectionOrNull())

        val json = collection.toJson()
        json.getJSONArray("recipeIds").put("").put("a")
        assertEquals(listOf("a", "b"), json.toRecipeCollectionOrNull()?.recipeIds)
    }
}
