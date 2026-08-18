package com.example.groceriesapp.data.recipes

import com.example.groceriesapp.data.fillFraction
import com.example.groceriesapp.data.InventoryItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class IngredientStatus {
    HAVE,
    PARTIAL,
    MISSING,
}

data class IngredientMatch(
    val ingredient: RecipeIngredient,
    /** Inventory rows with the same concept, earliest expiry first. Empty when MISSING. */
    val matchedItems: List<InventoryItem>,
    val status: IngredientStatus,
    val minDaysUntilExpiry: Long?,
)

data class RecipeMatch(
    val recipe: Recipe,
    val ingredientMatches: List<IngredientMatch>,
    /** Share of required (non-optional) ingredients available; PARTIAL counts half. */
    val coverage: Double,
    /** Required ingredients matched to something expiring within 3 days (or already expired). */
    val expiringUsed: Int,
    val missingRequired: List<RecipeIngredient>,
    val score: Double,
)

data class RecipeFilters(
    val useExpiringFirst: Boolean = true,
    val maxPrepMinutes: Int? = null,
    val onlyWhatIHave: Boolean = false,
    val favoritesOnly: Boolean = false,
    val query: String = "",
)

data class RecipeSections(
    val bestMatches: List<RecipeMatch>,
    val moreIdeas: List<RecipeMatch>,
)

/**
 * Pure inventory-vs-recipe matching and ranking. No Android dependencies; `today` is injected so
 * ordering is deterministic under test. Millis-to-date conversion uses the system zone, matching
 * how expiry dates are produced by [com.example.groceriesapp.data.taxonomy.ExpiryEstimator].
 */
class RecipeMatcher(private val zoneId: ZoneId = ZoneId.systemDefault()) {

    fun matchOne(
        recipe: Recipe,
        inventory: List<InventoryItem>,
        today: LocalDate,
        favoriteIds: Set<String> = emptySet(),
        useExpiringFirst: Boolean = true,
    ): RecipeMatch {
        // An item tracked down to a residue is not something to plan a meal around. Untracked
        // rows (null fraction) stay in — not knowing how much is left is not the same as knowing
        // there is none.
        val usable = inventory.filter { (it.fillFraction ?: 1f) > USED_UP_FRACTION }
        val byConcept = usable.filter { it.foodConceptId != null }.groupBy { it.foodConceptId }

        val matches = recipe.ingredients.map { ingredient ->
            val candidates = ingredient.foodConceptId?.let { byConcept[it] }.orEmpty()
                .sortedWith(compareBy(nullsLast()) { it.expiresAtEpochMillis })
            IngredientMatch(
                ingredient = ingredient,
                matchedItems = candidates,
                status = statusOf(ingredient, candidates),
                minDaysUntilExpiry = candidates
                    .mapNotNull { it.expiresAtEpochMillis }
                    .minOrNull()
                    ?.let { daysUntil(it, today) },
            )
        }

        val required = matches.filter { !it.ingredient.optional }
        val coverage = if (required.isEmpty()) {
            0.0
        } else {
            required.sumOf {
                when (it.status) {
                    IngredientStatus.HAVE -> 1.0
                    IngredientStatus.PARTIAL -> 0.5
                    IngredientStatus.MISSING -> 0.0
                }
            } / required.size
        }
        val expiringUsed = required.count {
            it.status != IngredientStatus.MISSING && (it.minDaysUntilExpiry ?: Long.MAX_VALUE) <= 3
        }
        val missingRequired = required
            .filter { it.status == IngredientStatus.MISSING }
            .map { it.ingredient }

        return RecipeMatch(
            recipe = recipe,
            ingredientMatches = matches,
            coverage = coverage,
            expiringUsed = expiringUsed,
            missingRequired = missingRequired,
            score = score(
                recipe = recipe,
                required = required,
                coverage = coverage,
                missingCount = missingRequired.size,
                favorite = recipe.id in favoriteIds,
                useExpiringFirst = useExpiringFirst,
            ),
        )
    }

    fun sections(
        recipes: List<Recipe>,
        inventory: List<InventoryItem>,
        today: LocalDate,
        filters: RecipeFilters = RecipeFilters(),
        favoriteIds: Set<String> = emptySet(),
    ): RecipeSections {
        val query = filters.query.trim()
        val visible = recipes
            .filter { filters.maxPrepMinutes == null || it.prepMinutes <= filters.maxPrepMinutes }
            .filter { !filters.favoritesOnly || it.id in favoriteIds }
            .filter { query.isEmpty() || it.matchesQuery(query) }

        val ranked = visible
            .map { matchOne(it, inventory, today, favoriteIds, filters.useExpiringFirst) }
            .filter { !filters.onlyWhatIHave || it.missingRequired.isEmpty() }
            .sortedByDescending { it.score }

        val best = ranked.filter { it.coverage >= BEST_MATCH_COVERAGE }.take(BEST_MATCH_LIMIT)
        return RecipeSections(
            bestMatches = best,
            moreIdeas = ranked - best.toSet(),
        )
    }

    private fun statusOf(
        ingredient: RecipeIngredient,
        candidates: List<InventoryItem>,
    ): IngredientStatus {
        if (candidates.isEmpty()) return IngredientStatus.MISSING
        val needed = UnitNormalizer.normalize(ingredient.amount, ingredient.unit)
            ?: return IngredientStatus.HAVE
        if (needed.kind == UnitKind.UNKNOWN) return IngredientStatus.HAVE
        val comparable = candidates
            .mapNotNull { UnitNormalizer.normalize(it.quantity, it.unit) }
            .filter { it.kind == needed.kind }
        // Nothing in a comparable unit: the item exists, so match on presence rather than guess.
        if (comparable.isEmpty()) return IngredientStatus.HAVE
        val available = comparable.sumOf { it.value }
        return if (available >= needed.value) IngredientStatus.HAVE else IngredientStatus.PARTIAL
    }

    private fun score(
        recipe: Recipe,
        required: List<IngredientMatch>,
        coverage: Double,
        missingCount: Int,
        favorite: Boolean,
        useExpiringFirst: Boolean,
    ): Double {
        val expiryScore = (
            required
                .filter { it.status != IngredientStatus.MISSING }
                .sumOf { urgencyWeight(it.minDaysUntilExpiry) } / 2.0
            ).coerceAtMost(1.0)
        val timeScore = when {
            recipe.prepMinutes <= 20 -> 1.0
            recipe.prepMinutes <= 30 -> 0.7
            recipe.prepMinutes <= 45 -> 0.4
            else -> 0.2
        }
        val simplicity = 1.0 - (missingCount / 4.0).coerceAtMost(1.0)
        val (coverageWeight, expiryWeight) = if (useExpiringFirst) 0.30 to 0.45 else 0.45 to 0.30
        return coverageWeight * coverage +
            expiryWeight * expiryScore +
            0.15 * timeScore +
            0.10 * simplicity +
            if (favorite) 0.05 else 0.0
    }

    private fun urgencyWeight(daysUntil: Long?): Double = when {
        daysUntil == null -> 0.0
        daysUntil <= 0 -> 1.0
        daysUntil == 1L -> 0.75
        daysUntil == 2L -> 0.5
        daysUntil == 3L -> 0.25
        else -> 0.0
    }

    private fun daysUntil(epochMillis: Long, today: LocalDate): Long {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
        return ChronoUnit.DAYS.between(today, date)
    }

    private fun Recipe.matchesQuery(query: String): Boolean =
        title.contains(query, ignoreCase = true) ||
            ingredients.any { it.displayName.contains(query, ignoreCase = true) }

    private companion object {
        const val BEST_MATCH_COVERAGE = 0.75
        const val BEST_MATCH_LIMIT = 5

        /** At or below this share of a pack an item is a residue, not an ingredient. */
        const val USED_UP_FRACTION = 0.05f
    }
}
