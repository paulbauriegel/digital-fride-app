package com.example.groceriesapp.data.recipes

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the repository's own file I/O, possible because it takes a root [File] and has its
 * network collaborators injected (here: none at all — every AI path must degrade, not crash).
 */
class RecipesRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private var now = 1_000L

    private val curated = listOf(
        Recipe(
            id = "cur1",
            title = "Curated",
            ingredients = listOf(RecipeIngredient("EGGS", "Eggs", 2.0, "pc")),
            steps = listOf("Cook."),
            prepMinutes = 10,
        ),
    )

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("recipes")
    }

    private fun repository() = RecipesRepository(
        baseDirectory = root,
        curatedRecipes = curated,
        aiDependencies = null,
        clock = { now },
    )

    private fun userRecipe(id: String) = Recipe(
        id = id,
        title = "User $id",
        ingredients = listOf(RecipeIngredient(null, "Salt")),
        steps = listOf("Season."),
        prepMinutes = 5,
        source = RecipeSource.USER,
    )

    @Test
    fun `starts empty when no files exist`() {
        val repository = repository()
        assertTrue(repository.userRecipes.value.isEmpty())
        assertTrue(repository.cookLog.value.isEmpty())
        assertTrue(repository.collections.value.isEmpty())
        assertEquals(curated, repository.allRecipes())
        assertFalse(repository.aiEnabled())
    }

    @Test
    fun `recordCooked persists newest first and reloads in a fresh instance`() {
        val repository = repository()
        now = 100L
        repository.recordCooked("cur1", "Curated", householdId = "h1", rating = 4)
        now = 200L
        val second = repository.recordCooked("cur1", "Curated", householdId = "h1")

        assertEquals(listOf(200L, 100L), repository.cookLog.value.map { it.cookedAtEpochMillis })

        val reloaded = repository()
        assertEquals(repository.cookLog.value, reloaded.cookLog.value)
        assertEquals(4, reloaded.cookLog.value.last().rating)

        reloaded.rateCookEntry(second.id, 5)
        assertEquals(5, repository().cookLog.value.first().rating)
        reloaded.deleteCookEntry(second.id)
        assertEquals(1, repository().cookLog.value.size)
    }

    @Test
    fun `legacy favorites file format is still read`() {
        File(root, "favorites.json").writeText(
            JSONObject().put("schemaVersion", 1).put("ids", JSONArray(listOf("cur1"))).toString(),
        )
        val repository = repository()
        assertEquals(setOf("cur1"), repository.favoriteIds.value)
        repository.toggleFavorite("cur1")
        assertTrue(repository().favoriteIds.value.isEmpty())
    }

    @Test
    fun `collections are created renamed filled and deleted`() {
        val repository = repository()
        repository.saveUserRecipe(userRecipe("u1"))
        val collection = repository.createCollection("  Weeknight  ")
        assertEquals("Weeknight", collection.name)

        repository.setRecipeInCollection(collection.id, "u1", included = true)
        repository.setRecipeInCollection(collection.id, "u1", included = true)
        assertEquals(listOf("u1"), repository().collections.value.single().recipeIds)

        repository.renameCollection(collection.id, "Fast")
        assertEquals("Fast", repository().collections.value.single().name)

        repository.setRecipeInCollection(collection.id, "u1", included = false)
        assertTrue(repository().collections.value.single().recipeIds.isEmpty())

        repository.deleteCollection(collection.id)
        assertTrue(repository().collections.value.isEmpty())
    }

    @Test
    fun `deleting a recipe removes it from collections but keeps its cook history`() {
        val repository = repository()
        repository.saveUserRecipe(userRecipe("u1"))
        val collection = repository.createCollection("Fast")
        repository.setRecipeInCollection(collection.id, "u1", included = true)
        repository.toggleFavorite("u1")
        repository.recordCooked("u1", "User u1", householdId = null)

        repository.deleteRecipe("u1")

        val reloaded = repository()
        assertTrue(reloaded.userRecipes.value.isEmpty())
        assertTrue(reloaded.collections.value.single().recipeIds.isEmpty())
        assertTrue(reloaded.favoriteIds.value.isEmpty())
        assertEquals("User u1", reloaded.cookLog.value.single().recipeTitle)
    }

    @Test
    fun `saveUserRecipe upserts and forces the user source`() {
        val repository = repository()
        repository.saveUserRecipe(userRecipe("u1").copy(source = RecipeSource.AI))
        repository.saveUserRecipe(userRecipe("u1").copy(title = "Renamed"))

        val stored = repository().userRecipes.value.single()
        assertEquals("Renamed", stored.title)
        assertEquals(RecipeSource.USER, stored.source)
        assertNotNull(repository.recipeById("u1"))
    }

    @Test
    fun `malformed cook log entries are skipped not fatal`() {
        File(root, "cooklog.json").writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put(
                    "entries",
                    JSONArray()
                        .put(JSONObject().put("id", "bad"))
                        .put(CookEntry("cur1", "Curated", 5L, id = "good").toJson()),
                )
                .toString(),
        )
        assertEquals(listOf("good"), repository().cookLog.value.map { it.id })
    }

    @Test
    fun `recent sources are counted deduplicated capped and persisted`() {
        val repository = repository()
        now = 10L
        repository.recordRecentSource("chefkoch.de")
        now = 20L
        repository.recordRecentSource("example.com")
        now = 30L
        repository.recordRecentSource("chefkoch.de")

        val sources = repository().recentSources.value
        assertEquals(listOf("chefkoch.de", "example.com"), sources.map { it.host })
        assertEquals(2, sources.first().count)
        assertEquals(30L, sources.first().lastUsedAtEpochMillis)
        assertEquals("Chefkoch", sources.first().name)

        repeat(12) { repository.recordRecentSource("site$it.test") }
        assertEquals(8, repository().recentSources.value.size)
    }

    @Test
    fun `discarding a review draft is safe when there is none`() {
        val repository = repository()
        repository.discardReviewDraft()
        assertTrue(repository.reviewDraft.value == null)
    }

    @Test
    fun `cook log backfill runs once per household and skips recipes that already have history`() {
        val repository = repository()
        repository.recordCooked("known", "Known", householdId = "h1", cookedAtEpochMillis = 50L)
        val events = listOf(
            com.example.groceriesapp.data.ConsumptionEvent(
                itemId = "a", name = "Eggs", reason = com.example.groceriesapp.data.ConsumptionReason.COOKED,
                recipeId = "cur1", recipeTitle = "Curated", removedAtEpochMillis = 10L,
            ),
            com.example.groceriesapp.data.ConsumptionEvent(
                itemId = "b", name = "Eggs", reason = com.example.groceriesapp.data.ConsumptionReason.COOKED,
                recipeId = "known", recipeTitle = "Known", removedAtEpochMillis = 20L,
            ),
        )

        repository.backfillCookLog("h1", events)
        repository.backfillCookLog("h1", events)

        val log = repository().cookLog.value
        assertEquals(listOf("known", "cur1"), log.map { it.recipeId })
        assertEquals("h1", log.last().householdId)
        assertTrue(File(root, "cooklog.backfilled.h1").isFile)
    }
}
