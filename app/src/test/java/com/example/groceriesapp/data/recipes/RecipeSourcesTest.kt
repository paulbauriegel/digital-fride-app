package com.example.groceriesapp.data.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeSourcesTest {

    @Test
    fun `host extraction strips scheme www and mobile prefixes`() {
        assertEquals("chefkoch.de", RecipeSources.hostOf("https://www.chefkoch.de/rezepte/123"))
        assertEquals("bbcgoodfood.com", RecipeSources.hostOf("m.bbcgoodfood.com/recipes/x"))
        assertEquals("example.org", RecipeSources.hostOf("example.org"))
        assertNull(RecipeSources.hostOf(""))
        assertNull(RecipeSources.hostOf("not a url at all"))
    }

    @Test
    fun `matching finds curated sites including subdomains`() {
        assertEquals("chefkoch", RecipeSources.matching("https://www.chefkoch.de/rezepte/1")?.id)
        assertEquals("allrecipes", RecipeSources.matching("https://www.allrecipes.com/recipe/2")?.id)
        assertEquals("bbcgoodfood", RecipeSources.matching("https://recipes.bbcgoodfood.com/x")?.id)
        assertNull(RecipeSources.matching("https://example.com/recipe"))
    }

    @Test
    fun `display name prefers the curated name`() {
        assertEquals("Chefkoch", RecipeSources.displayName("chefkoch.de"))
        assertEquals("example.com", RecipeSources.displayName("example.com"))
    }

    @Test
    fun `curated ids and hosts are unique`() {
        assertEquals(RecipeSources.curated.size, RecipeSources.curated.map { it.id }.toSet().size)
        assertEquals(RecipeSources.curated.size, RecipeSources.curated.map { it.host }.toSet().size)
        assertTrue(RecipeSources.curated.all { it.homeUrl.startsWith("https://") })
    }

    @Test
    fun `recent sources round trip`() {
        val source = RecentSource(host = "chefkoch.de", name = "Chefkoch", lastUsedAtEpochMillis = 7L, count = 3)
        assertEquals(source, source.toJson().toRecentSourceOrNull())
    }
}
