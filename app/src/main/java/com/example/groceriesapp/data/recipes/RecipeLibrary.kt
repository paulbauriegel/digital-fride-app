package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.InventoryItem
import java.time.LocalDate

enum class LibraryTab(val label: String) {
    ALL("All"),
    SAVED("Saved"),
    COOKED("Cooked"),
    COLLECTIONS("Collections"),
}

enum class RecipeSort(val label: String, val description: String) {
    BEST_MATCH("Best match", "What you can cook now"),
    RECENTLY_COOKED("Recently cooked", "Newest first"),
    RECENTLY_ADDED("Recently added", "Newest first"),
    COOK_TIME_SHORTEST("Cooking time", "Shortest first"),
    COOK_TIME_LONGEST("Cooking time", "Longest first"),
    NAME_AZ("Name", "A → Z"),
    NAME_ZA("Name", "Z → A"),
    RATING("Rating", "Highest first"),
    MOST_COOKED("Most cooked", "Most often cooked first"),
}

enum class OrganizeBy(val label: String) {
    NONE("Flat list"),
    COLLECTIONS("Collections"),
    CATEGORIES("Categories"),
    MEAL_TYPE("Meal type"),
    TAGS("Tags"),
}

/**
 * Everything the library screen, its Filters sheet and its Sort sheet agree on. Empty sets mean
 * "All". [includeIngredients] / [excludeIngredients] hold food-concept ids or lower-case names.
 */
data class RecipeLibraryQuery(
    val tab: LibraryTab = LibraryTab.SAVED,
    val categories: Set<RecipeCategory> = emptySet(),
    val cookTimes: Set<CookTimeBucket> = emptySet(),
    val dietary: Set<DietaryTag> = emptySet(),
    val includeIngredients: Set<String> = emptySet(),
    val excludeIngredients: Set<String> = emptySet(),
    val useExpiringFirst: Boolean = false,
    val onlyWhatICanMake: Boolean = false,
    val cookedByMe: Boolean = false,
    val favoritesOnly: Boolean = false,
    val query: String = "",
    val sort: RecipeSort = RecipeSort.RECENTLY_ADDED,
    val organizeBy: OrganizeBy = OrganizeBy.NONE,
    val collectionId: String? = null,
) {
    /** How many filter dimensions are narrowing the list — the badge on the Filters button. */
    val activeFilterCount: Int
        get() = listOf(
            categories.isNotEmpty(),
            cookTimes.isNotEmpty(),
            dietary.isNotEmpty(),
            includeIngredients.isNotEmpty(),
            excludeIngredients.isNotEmpty(),
            useExpiringFirst,
            onlyWhatICanMake,
            cookedByMe,
            favoritesOnly,
        ).count { it }

    /** The same query with every filter cleared but the tab, sort and grouping kept. */
    fun withFiltersReset(): RecipeLibraryQuery = RecipeLibraryQuery(
        tab = tab,
        query = query,
        sort = sort,
        organizeBy = organizeBy,
        collectionId = collectionId,
    )
}

data class RecipeStats(
    val timesCooked: Int,
    val lastCookedAtEpochMillis: Long?,
    val averageRating: Double?,
    val ratingCount: Int,
) {
    companion object {
        val NONE = RecipeStats(timesCooked = 0, lastCookedAtEpochMillis = null, averageRating = null, ratingCount = 0)
    }
}

data class LibraryRow(
    val match: RecipeMatch,
    val stats: RecipeStats,
    val favorite: Boolean,
    val category: RecipeCategory,
    val dietary: Set<DietaryTag>,
) {
    val recipe: Recipe get() = match.recipe
}

data class LibraryGroup(
    /** Null for the single ungrouped list. */
    val title: String?,
    val rows: List<LibraryRow>,
)

data class HomeCounts(
    val favorites: Int,
    val expiringMatches: Int,
    val over30Minutes: Int,
)

/**
 * Pure filtering, sorting and grouping over the recipe library. No Android dependencies; `today`
 * is injected so results are deterministic under test. The inventory matching itself is
 * delegated to [RecipeMatcher] — this class only decides which recipes are shown and in what order.
 */
class RecipeLibrary(private val matcher: RecipeMatcher = RecipeMatcher()) {

    fun statsFor(cookLog: List<CookEntry>): Map<String, RecipeStats> =
        cookLog.groupBy { it.recipeId }.mapValues { (_, entries) ->
            val ratings = entries.mapNotNull { it.rating }
            RecipeStats(
                timesCooked = entries.size,
                lastCookedAtEpochMillis = entries.maxOf { it.cookedAtEpochMillis },
                averageRating = ratings.takeIf { it.isNotEmpty() }?.average(),
                ratingCount = ratings.size,
            )
        }

    fun rows(
        recipes: List<Recipe>,
        inventory: List<InventoryItem>,
        favoriteIds: Set<String>,
        cookLog: List<CookEntry>,
        collections: List<RecipeCollection>,
        today: LocalDate,
        query: RecipeLibraryQuery,
    ): List<LibraryGroup> {
        val stats = statsFor(cookLog)
        val rows = visibleRows(recipes, inventory, favoriteIds, stats, collections, today, query)
        val sorted = rows.sortedWith(comparatorFor(query.sort))
        return group(sorted, collections, query.organizeBy)
    }

    fun count(
        recipes: List<Recipe>,
        inventory: List<InventoryItem>,
        favoriteIds: Set<String>,
        cookLog: List<CookEntry>,
        collections: List<RecipeCollection>,
        today: LocalDate,
        query: RecipeLibraryQuery,
    ): Int = visibleRows(recipes, inventory, favoriteIds, statsFor(cookLog), collections, today, query).size

    fun homeCounts(
        recipes: List<Recipe>,
        inventory: List<InventoryItem>,
        favoriteIds: Set<String>,
        today: LocalDate,
    ): HomeCounts {
        val ids = recipes.map { it.id }.toSet()
        return HomeCounts(
            favorites = favoriteIds.count { it in ids },
            expiringMatches = recipes.count { recipe ->
                matcher.matchOne(recipe, inventory, today, favoriteIds).expiringUsed >= 1
            },
            over30Minutes = recipes.count { it.prepMinutes > 30 },
        )
    }

    /** Distinct recipes by their most recent cook, newest first; deleted recipes are skipped. */
    fun recentlyCooked(
        recipes: List<Recipe>,
        cookLog: List<CookEntry>,
        limit: Int = 10,
    ): List<Pair<Recipe, CookEntry>> {
        val byId = recipes.associateBy { it.id }
        return cookLog
            .sortedByDescending { it.cookedAtEpochMillis }
            .distinctBy { it.recipeId }
            .mapNotNull { entry -> byId[entry.recipeId]?.let { it to entry } }
            .take(limit)
    }

    private fun visibleRows(
        recipes: List<Recipe>,
        inventory: List<InventoryItem>,
        favoriteIds: Set<String>,
        stats: Map<String, RecipeStats>,
        collections: List<RecipeCollection>,
        today: LocalDate,
        query: RecipeLibraryQuery,
    ): List<LibraryRow> {
        val inScope = recipes.filter { recipe ->
            when (query.tab) {
                LibraryTab.ALL -> true
                LibraryTab.SAVED ->
                    recipe.id in favoriteIds || recipe.source != RecipeSource.CURATED
                LibraryTab.COOKED -> recipe.id in stats
                LibraryTab.COLLECTIONS -> {
                    val collectionId = query.collectionId
                    if (collectionId != null) {
                        collections.firstOrNull { it.id == collectionId }
                            ?.recipeIds?.contains(recipe.id) == true
                    } else {
                        collections.any { recipe.id in it.recipeIds }
                    }
                }
            }
        }
        val text = query.query.trim()
        return inScope
            .asSequence()
            .filter { query.categories.isEmpty() || RecipeTaxonomy.categoryOf(it) in query.categories }
            .filter { query.cookTimes.isEmpty() || RecipeTaxonomy.bucketOf(it.prepMinutes) in query.cookTimes }
            .filter { recipe ->
                query.dietary.isEmpty() || RecipeTaxonomy.dietaryOf(recipe).containsAll(query.dietary)
            }
            .filter { recipe -> query.includeIngredients.all { recipe.usesIngredient(it) } }
            .filter { recipe -> query.excludeIngredients.none { recipe.usesIngredient(it) } }
            .filter { !query.favoritesOnly || it.id in favoriteIds }
            .filter { !query.cookedByMe || it.id in stats }
            .filter { text.isEmpty() || it.matchesText(text) }
            .map { recipe ->
                LibraryRow(
                    match = matcher.matchOne(
                        recipe = recipe,
                        inventory = inventory,
                        today = today,
                        favoriteIds = favoriteIds,
                        useExpiringFirst = query.useExpiringFirst,
                    ),
                    stats = stats[recipe.id] ?: RecipeStats.NONE,
                    favorite = recipe.id in favoriteIds,
                    category = RecipeTaxonomy.categoryOf(recipe),
                    dietary = RecipeTaxonomy.dietaryOf(recipe),
                )
            }
            .filter { !query.onlyWhatICanMake || it.match.missingRequired.isEmpty() }
            .toList()
    }

    private fun comparatorFor(sort: RecipeSort): Comparator<LibraryRow> {
        val byTitle = compareBy<LibraryRow, String>(String.CASE_INSENSITIVE_ORDER) { it.recipe.title }
        val byLastCooked = compareByDescending<LibraryRow> { it.stats.lastCookedAtEpochMillis ?: Long.MIN_VALUE }
        val byScore = compareByDescending<LibraryRow> { it.match.score }
        return when (sort) {
            RecipeSort.BEST_MATCH -> byScore.then(byTitle)
            RecipeSort.RECENTLY_COOKED -> byLastCooked.then(byTitle)
            // Curated recipes carry no creation time; they trail everything the user added.
            RecipeSort.RECENTLY_ADDED ->
                compareByDescending<LibraryRow> { it.recipe.createdAtEpochMillis ?: Long.MIN_VALUE }
                    .then(byTitle)
            RecipeSort.COOK_TIME_SHORTEST -> compareBy<LibraryRow> { it.recipe.prepMinutes }.then(byTitle)
            RecipeSort.COOK_TIME_LONGEST -> compareByDescending<LibraryRow> { it.recipe.prepMinutes }.then(byTitle)
            RecipeSort.NAME_AZ -> byTitle
            RecipeSort.NAME_ZA -> byTitle.reversed()
            RecipeSort.RATING ->
                compareByDescending<LibraryRow> { it.stats.averageRating ?: -1.0 }
                    .then(byLastCooked)
                    .then(byTitle)
            RecipeSort.MOST_COOKED ->
                compareByDescending<LibraryRow> { it.stats.timesCooked }
                    .then(byLastCooked)
                    .then(byTitle)
        }
    }

    private fun group(
        rows: List<LibraryRow>,
        collections: List<RecipeCollection>,
        organizeBy: OrganizeBy,
    ): List<LibraryGroup> = when (organizeBy) {
        OrganizeBy.NONE -> listOf(LibraryGroup(title = null, rows = rows))
        OrganizeBy.CATEGORIES -> RecipeCategory.entries
            .map { category -> LibraryGroup(category.label, rows.filter { it.category == category }) }
            .filter { it.rows.isNotEmpty() }
        OrganizeBy.MEAL_TYPE -> {
            val meals = listOf(
                RecipeCategory.BREAKFAST,
                RecipeCategory.LUNCH,
                RecipeCategory.DINNER,
                RecipeCategory.SNACK,
            )
            val grouped = meals.map { category ->
                LibraryGroup(category.label, rows.filter { it.category == category })
            }
            val rest = rows.filter { it.category !in meals }
            (grouped + LibraryGroup("Other", rest)).filter { it.rows.isNotEmpty() }
        }
        OrganizeBy.TAGS -> {
            val tagged = rows.flatMap { row -> RecipeTaxonomy.freeTagsOf(row.recipe).map { it to row } }
            val groups = tagged.groupBy({ it.first }, { it.second })
                .toSortedMap()
                .map { (tag, members) -> LibraryGroup(tag, members) }
            val untagged = rows.filter { RecipeTaxonomy.freeTagsOf(it.recipe).isEmpty() }
            (groups + LibraryGroup("No tags", untagged)).filter { it.rows.isNotEmpty() }
        }
        OrganizeBy.COLLECTIONS -> {
            val inAny = collections.flatMap { it.recipeIds }.toSet()
            val groups = collections.map { collection ->
                LibraryGroup(collection.name, rows.filter { it.recipe.id in collection.recipeIds })
            }
            val loose = rows.filter { it.recipe.id !in inAny }
            (groups + LibraryGroup("Not in a collection", loose)).filter { it.rows.isNotEmpty() }
        }
    }

    private fun Recipe.usesIngredient(key: String): Boolean {
        val needle = key.trim().lowercase()
        if (needle.isEmpty()) return false
        return ingredients.any { ingredient ->
            ingredient.foodConceptId?.lowercase() == needle ||
                ingredient.displayName.lowercase().contains(needle)
        }
    }

    private fun Recipe.matchesText(text: String): Boolean =
        title.contains(text, ignoreCase = true) ||
            ingredients.any { it.displayName.contains(text, ignoreCase = true) } ||
            tags.any { it.contains(text, ignoreCase = true) }
}
