package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.InventoryItem
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeLibraryTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.of(2026, 8, 21)
    private val library = RecipeLibrary(RecipeMatcher(zone))

    private fun millis(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun recipe(
        id: String,
        title: String = id,
        prepMinutes: Int = 20,
        tags: List<String> = emptyList(),
        source: RecipeSource = RecipeSource.CURATED,
        createdAt: Long? = null,
        ingredients: List<RecipeIngredient> = listOf(RecipeIngredient("EGGS", "Eggs", 2.0, "pc")),
        steps: Int = 3,
    ) = Recipe(
        id = id,
        title = title,
        ingredients = ingredients,
        steps = List(steps) { "Step $it" },
        prepMinutes = prepMinutes,
        tags = tags,
        source = source,
        createdAtEpochMillis = createdAt,
    )

    private fun cooked(recipeId: String, daysAgo: Long, rating: Int? = null) = CookEntry(
        recipeId = recipeId,
        recipeTitle = recipeId,
        cookedAtEpochMillis = millis(today.minusDays(daysAgo)),
        rating = rating,
    )

    private val eggs = InventoryItem(name = "Eier", foodConceptId = "EGGS", quantity = 6.0, unit = "pc")

    private val pancakes = recipe("pancakes", "Pancakes", prepMinutes = 15, tags = listOf("breakfast", "vegetarian"))
    private val curry = recipe(
        "curry", "Chickpea curry", prepMinutes = 45, tags = listOf("vegan", "dinner"),
        ingredients = listOf(RecipeIngredient("CHICKPEAS", "Chickpeas", 400.0, "g"), RecipeIngredient("RICE", "Rice", 200.0, "g")),
    )
    private val omelette = recipe("omelette", "Omelette", prepMinutes = 10, source = RecipeSource.USER, createdAt = 200L)
    private val roast = recipe("roast", "Sunday roast", prepMinutes = 120, source = RecipeSource.AI, createdAt = 100L, tags = listOf("oven"))
    private val all = listOf(pancakes, curry, omelette, roast)

    private fun rows(
        query: RecipeLibraryQuery,
        recipes: List<Recipe> = all,
        favorites: Set<String> = emptySet(),
        cookLog: List<CookEntry> = emptyList(),
        collections: List<RecipeCollection> = emptyList(),
        inventory: List<InventoryItem> = listOf(eggs),
    ): List<String> = library.rows(recipes, inventory, favorites, cookLog, collections, today, query)
        .flatMap { it.rows }
        .map { it.recipe.id }

    @Test
    fun `stats aggregate count last cooked and average rating`() {
        val stats = library.statsFor(listOf(cooked("a", 5, 4), cooked("a", 1, 5), cooked("a", 3), cooked("b", 2)))

        assertEquals(3, stats.getValue("a").timesCooked)
        assertEquals(millis(today.minusDays(1)), stats.getValue("a").lastCookedAtEpochMillis)
        assertEquals(4.5, stats.getValue("a").averageRating!!, 1e-9)
        assertEquals(2, stats.getValue("a").ratingCount)
        assertNull(stats.getValue("b").averageRating)
    }

    @Test
    fun `saved tab holds favourites and everything the user owns but not plain curated`() {
        assertEquals(
            setOf("curry", "omelette", "roast"),
            rows(RecipeLibraryQuery(tab = LibraryTab.SAVED), favorites = setOf("curry")).toSet(),
        )
    }

    @Test
    fun `cooked tab only lists recipes with a cook entry`() {
        assertEquals(
            listOf("pancakes"),
            rows(RecipeLibraryQuery(tab = LibraryTab.COOKED), cookLog = listOf(cooked("pancakes", 1))),
        )
    }

    @Test
    fun `collections tab scopes to one collection or to any`() {
        val collections = listOf(
            RecipeCollection(id = "c1", name = "Fast", recipeIds = listOf("omelette"), createdAtEpochMillis = 1L),
            RecipeCollection(id = "c2", name = "Slow", recipeIds = listOf("roast"), createdAtEpochMillis = 2L),
        )
        assertEquals(
            listOf("omelette"),
            rows(RecipeLibraryQuery(tab = LibraryTab.COLLECTIONS, collectionId = "c1"), collections = collections),
        )
        assertEquals(
            setOf("omelette", "roast"),
            rows(RecipeLibraryQuery(tab = LibraryTab.COLLECTIONS), collections = collections).toSet(),
        )
    }

    @Test
    fun `category cook time and dietary filters narrow the list`() {
        val base = RecipeLibraryQuery(tab = LibraryTab.ALL, sort = RecipeSort.NAME_AZ)
        assertEquals(listOf("pancakes"), rows(base.copy(categories = setOf(RecipeCategory.BREAKFAST))))
        assertEquals(listOf("omelette"), rows(base.copy(cookTimes = setOf(CookTimeBucket.UNDER_15))))
        assertEquals(listOf("roast"), rows(base.copy(cookTimes = setOf(CookTimeBucket.OVER_60))))
        // Vegan curry is vegetarian too.
        assertEquals(listOf("curry", "pancakes"), rows(base.copy(dietary = setOf(DietaryTag.VEGETARIAN))))
        assertEquals(listOf("curry"), rows(base.copy(dietary = setOf(DietaryTag.VEGAN))))
    }

    @Test
    fun `include and exclude ingredients match concept ids and names`() {
        val base = RecipeLibraryQuery(tab = LibraryTab.ALL, sort = RecipeSort.NAME_AZ)
        assertEquals(listOf("curry"), rows(base.copy(includeIngredients = setOf("CHICKPEAS"))))
        assertEquals(listOf("curry"), rows(base.copy(includeIngredients = setOf("rice"))))
        assertEquals(listOf("curry"), rows(base.copy(excludeIngredients = setOf("eggs"))))
        assertTrue(rows(base.copy(includeIngredients = setOf("rice", "eggs"))).isEmpty())
    }

    @Test
    fun `only what I can make cooked by me favourites and text filters`() {
        val base = RecipeLibraryQuery(tab = LibraryTab.ALL, sort = RecipeSort.NAME_AZ)
        assertEquals(listOf("omelette", "pancakes", "roast"), rows(base.copy(onlyWhatICanMake = true)))
        assertEquals(listOf("roast"), rows(base.copy(cookedByMe = true), cookLog = listOf(cooked("roast", 2))))
        assertEquals(listOf("curry"), rows(base.copy(favoritesOnly = true), favorites = setOf("curry")))
        assertEquals(listOf("curry"), rows(base.copy(query = "chick")))
        assertEquals(listOf("roast"), rows(base.copy(query = "oven")))
    }

    @Test
    fun `count matches the number of rows`() {
        val query = RecipeLibraryQuery(tab = LibraryTab.ALL, dietary = setOf(DietaryTag.VEGETARIAN))
        assertEquals(
            rows(query).size,
            library.count(all, listOf(eggs), emptySet(), emptyList(), emptyList(), today, query),
        )
    }

    @Test
    fun `every sort orders as labelled`() {
        val log = listOf(cooked("curry", 1, 3), cooked("pancakes", 5, 5), cooked("pancakes", 9, 4), cooked("omelette", 2))
        fun sorted(sort: RecipeSort) = rows(RecipeLibraryQuery(tab = LibraryTab.ALL, sort = sort), cookLog = log)

        assertEquals(listOf("curry", "omelette", "pancakes", "roast"), sorted(RecipeSort.RECENTLY_COOKED))
        assertEquals(listOf("omelette", "roast", "curry", "pancakes"), sorted(RecipeSort.RECENTLY_ADDED))
        assertEquals(listOf("omelette", "pancakes", "curry", "roast"), sorted(RecipeSort.COOK_TIME_SHORTEST))
        assertEquals(listOf("roast", "curry", "pancakes", "omelette"), sorted(RecipeSort.COOK_TIME_LONGEST))
        assertEquals(listOf("curry", "omelette", "pancakes", "roast"), sorted(RecipeSort.NAME_AZ))
        assertEquals(listOf("roast", "pancakes", "omelette", "curry"), sorted(RecipeSort.NAME_ZA))
        // Rated first (4.5 then 3), then unrated by last cooked.
        assertEquals(listOf("pancakes", "curry", "omelette", "roast"), sorted(RecipeSort.RATING))
        // Two cooks, then single cooks by recency, then never cooked.
        assertEquals(listOf("pancakes", "curry", "omelette", "roast"), sorted(RecipeSort.MOST_COOKED))
    }

    @Test
    fun `best match puts what can be cooked now first`() {
        assertEquals("curry", rows(RecipeLibraryQuery(tab = LibraryTab.ALL, sort = RecipeSort.BEST_MATCH)).last())
    }

    @Test
    fun `organize by categories meal type tags and collections`() {
        fun groups(organizeBy: OrganizeBy, collections: List<RecipeCollection> = emptyList()) =
            library.rows(all, listOf(eggs), emptySet(), emptyList(), collections, today, RecipeLibraryQuery(tab = LibraryTab.ALL, sort = RecipeSort.NAME_AZ, organizeBy = organizeBy))
                .map { it.title to it.rows.map { row -> row.recipe.id } }

        assertEquals(
            listOf("Breakfast" to listOf("pancakes"), "Dinner" to listOf("curry", "omelette", "roast")),
            groups(OrganizeBy.CATEGORIES),
        )
        assertEquals(
            listOf("Breakfast" to listOf("pancakes"), "Dinner" to listOf("curry", "omelette", "roast")),
            groups(OrganizeBy.MEAL_TYPE),
        )
        assertEquals(
            listOf("oven" to listOf("roast"), "No tags" to listOf("curry", "omelette", "pancakes")),
            groups(OrganizeBy.TAGS),
        )
        val collections = listOf(RecipeCollection(id = "c", name = "Fast", recipeIds = listOf("omelette", "pancakes"), createdAtEpochMillis = 1L))
        assertEquals(
            listOf("Fast" to listOf("omelette", "pancakes"), "Not in a collection" to listOf("curry", "roast")),
            groups(OrganizeBy.COLLECTIONS, collections),
        )
    }

    @Test
    fun `home counts and recently cooked`() {
        val counts = library.homeCounts(all, listOf(eggs), setOf("curry", "gone"), today)
        assertEquals(1, counts.favorites)
        assertEquals(2, counts.over30Minutes)
        assertEquals(0, counts.expiringMatches)

        val recent = library.recentlyCooked(all, listOf(cooked("curry", 3), cooked("pancakes", 1), cooked("curry", 2), cooked("deleted", 0)))
        assertEquals(listOf("pancakes", "curry"), recent.map { it.first.id })
        assertEquals(millis(today.minusDays(2)), recent[1].second.cookedAtEpochMillis)
    }

    @Test
    fun `active filter count and reset`() {
        val query = RecipeLibraryQuery(
            tab = LibraryTab.COOKED,
            categories = setOf(RecipeCategory.DINNER),
            onlyWhatICanMake = true,
            sort = RecipeSort.RATING,
            query = "x",
        )
        assertEquals(2, query.activeFilterCount)
        val reset = query.withFiltersReset()
        assertEquals(0, reset.activeFilterCount)
        assertEquals(LibraryTab.COOKED, reset.tab)
        assertEquals(RecipeSort.RATING, reset.sort)
        assertEquals("x", reset.query)
    }
}
