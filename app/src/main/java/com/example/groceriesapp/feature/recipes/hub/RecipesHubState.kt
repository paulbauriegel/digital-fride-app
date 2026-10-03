package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.groceriesapp.data.recipes.CookTimeBucket
import com.example.groceriesapp.data.recipes.DietaryTag
import com.example.groceriesapp.data.recipes.GenerationPreference
import com.example.groceriesapp.data.recipes.GenerationTime
import com.example.groceriesapp.data.recipes.LibraryTab
import com.example.groceriesapp.data.recipes.OrganizeBy
import com.example.groceriesapp.data.recipes.RecipeCategory
import com.example.groceriesapp.data.recipes.RecipeLibraryQuery
import com.example.groceriesapp.data.recipes.RecipeSort

/** The screens inside the Recipes tab. The tab's bottom-nav entry stays put across all of them. */
enum class RecipesRoute {
    HOME,
    LIBRARY,
    FILTERS,
    SORT,
    IMPORT_METHOD,
    IMPORT_SOURCES,
    IMPORT_URL,
    SCAN,
    SCAN_REVIEW,
    GENERATE,
    GENERATE_RESULTS,
}

/**
 * Navigation and query state for the Recipes tab, held in one place so a recipe detail overlay
 * (which drops the tab subtree) and process death both restore the user to the same screen with
 * the same filters. Mirrors the hand-rolled enum-plus-early-return idiom the rest of the app
 * uses, just with a back stack because the hub is three levels deep.
 */
@Stable
class RecipesHubState internal constructor(
    initialRoute: RecipesRoute,
    initialStack: List<RecipesRoute>,
    initialQuery: RecipeLibraryQuery,
    initialDraft: RecipeLibraryQuery,
    initialImportUrlPrefill: String?,
    initialGenerationSelectedIds: Set<String> = emptySet(),
    initialGenerationPreferences: Set<GenerationPreference> = emptySet(),
    initialGenerationTime: GenerationTime = GenerationTime.ANY,
    initialViewedGenerationId: String? = null,
) {
    var route: RecipesRoute by mutableStateOf(initialRoute)
        private set

    private val stack = mutableStateListOf<RecipesRoute>().apply { addAll(initialStack) }

    /** The committed library query: what the list shows. */
    var query: RecipeLibraryQuery by mutableStateOf(initialQuery)

    /** The query being edited on the Filters and Sort screens; discarded on Back. */
    var draftQuery: RecipeLibraryQuery by mutableStateOf(initialDraft)

    /** A link to preload into the URL import screen, e.g. from the curated sources list. */
    var importUrlPrefill: String? by mutableStateOf(initialImportUrlPrefill)

    /** Inputs on the Generate screen; kept here so leaving for the results and coming back keeps them. */
    var generationSelectedIds: Set<String> by mutableStateOf(initialGenerationSelectedIds)
    var generationPreferences: Set<GenerationPreference> by mutableStateOf(initialGenerationPreferences)
    var generationTime: GenerationTime by mutableStateOf(initialGenerationTime)

    /** Which generation batch the results screen shows. */
    var viewedGenerationId: String? by mutableStateOf(initialViewedGenerationId)

    internal val stackSnapshot: List<RecipesRoute> get() = stack.toList()

    val canGoBack: Boolean get() = stack.isNotEmpty()

    fun navigate(to: RecipesRoute) {
        if (to == route) return
        stack += route
        route = to
    }

    /** Opens the library with [query], reusing the current LIBRARY entry instead of stacking. */
    fun openLibrary(query: RecipeLibraryQuery) {
        this.query = query
        if (route != RecipesRoute.LIBRARY) navigate(RecipesRoute.LIBRARY)
    }

    fun openFilters() {
        draftQuery = query
        navigate(RecipesRoute.FILTERS)
    }

    fun openSort() {
        draftQuery = query
        navigate(RecipesRoute.SORT)
    }

    /** Commits the draft and returns to the library. */
    fun applyDraft() {
        query = draftQuery
        back()
    }

    /** Returns false when already at the root, so the caller can let the system handle Back. */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        route = stack.removeAt(stack.lastIndex)
        return true
    }

    fun popToHome() {
        stack.clear()
        route = RecipesRoute.HOME
    }

    companion object {
        internal fun saver(): Saver<RecipesHubState, Any> = listSaver(
            save = { state ->
                listOf(
                    state.route.name,
                    state.stackSnapshot.joinToString(",") { it.name },
                    state.query.toSavedString(),
                    state.draftQuery.toSavedString(),
                    state.importUrlPrefill ?: "",
                    state.generationSelectedIds.joinToString(ITEM),
                    state.generationPreferences.joinToString(ITEM) { it.name },
                    state.generationTime.name,
                    state.viewedGenerationId ?: "",
                )
            },
            restore = { saved ->
                RecipesHubState(
                    initialRoute = RecipesRoute.entries.firstOrNull { it.name == saved[0] }
                        ?: RecipesRoute.HOME,
                    initialStack = saved[1].split(",")
                        .mapNotNull { name -> RecipesRoute.entries.firstOrNull { it.name == name } },
                    initialQuery = recipeLibraryQueryFromSaved(saved[2]),
                    initialDraft = recipeLibraryQueryFromSaved(saved[3]),
                    initialImportUrlPrefill = saved[4].takeIf(String::isNotEmpty),
                    initialGenerationSelectedIds = saved.getOrNull(5).orEmpty()
                        .split(ITEM).filter(String::isNotEmpty).toSet(),
                    initialGenerationPreferences = saved.getOrNull(6).orEmpty()
                        .split(ITEM)
                        .mapNotNull { name -> GenerationPreference.entries.firstOrNull { it.name == name } }
                        .toSet(),
                    initialGenerationTime = GenerationTime.entries
                        .firstOrNull { it.name == saved.getOrNull(7) } ?: GenerationTime.ANY,
                    initialViewedGenerationId = saved.getOrNull(8)?.takeIf(String::isNotEmpty),
                )
            },
        )
    }
}

@Composable
fun rememberRecipesHubState(): RecipesHubState = rememberSaveable(saver = RecipesHubState.saver()) {
    RecipesHubState(
        initialRoute = RecipesRoute.HOME,
        initialStack = emptyList(),
        initialQuery = RecipeLibraryQuery(),
        initialDraft = RecipeLibraryQuery(),
        initialImportUrlPrefill = null,
    )
}

// --- RecipeLibraryQuery <-> saved-state string ----------------------------------------------------
//
// Enum names and the free-text fields joined with separators that cannot appear in enum names;
// the ingredient keys and query text are URL-encoded so a user-typed "|" or ";" stays intact.

private const val FIELD = "|"
private const val ITEM = ";"

internal fun RecipeLibraryQuery.toSavedString(): String = listOf(
    tab.name,
    categories.joinToString(ITEM) { it.name },
    cookTimes.joinToString(ITEM) { it.name },
    dietary.joinToString(ITEM) { it.name },
    includeIngredients.joinToString(ITEM) { encode(it) },
    excludeIngredients.joinToString(ITEM) { encode(it) },
    useExpiringFirst.toString(),
    onlyWhatICanMake.toString(),
    cookedByMe.toString(),
    favoritesOnly.toString(),
    encode(query),
    sort.name,
    organizeBy.name,
    collectionId ?: "",
).joinToString(FIELD)

internal fun recipeLibraryQueryFromSaved(saved: String): RecipeLibraryQuery {
    val fields = saved.split(FIELD)
    if (fields.size < 14) return RecipeLibraryQuery()
    fun <T : Enum<T>> parseSet(index: Int, entries: List<T>): Set<T> =
        fields[index].split(ITEM).filter(String::isNotEmpty)
            .mapNotNull { name -> entries.firstOrNull { it.name == name } }
            .toSet()
    fun parseStrings(index: Int): Set<String> =
        fields[index].split(ITEM).filter(String::isNotEmpty).map(::decode).toSet()
    return RecipeLibraryQuery(
        tab = LibraryTab.entries.firstOrNull { it.name == fields[0] } ?: LibraryTab.SAVED,
        categories = parseSet(1, RecipeCategory.entries),
        cookTimes = parseSet(2, CookTimeBucket.entries),
        dietary = parseSet(3, DietaryTag.entries),
        includeIngredients = parseStrings(4),
        excludeIngredients = parseStrings(5),
        useExpiringFirst = fields[6].toBoolean(),
        onlyWhatICanMake = fields[7].toBoolean(),
        cookedByMe = fields[8].toBoolean(),
        favoritesOnly = fields[9].toBoolean(),
        query = decode(fields[10]),
        sort = RecipeSort.entries.firstOrNull { it.name == fields[11] } ?: RecipeSort.RECENTLY_ADDED,
        organizeBy = OrganizeBy.entries.firstOrNull { it.name == fields[12] } ?: OrganizeBy.NONE,
        collectionId = fields[13].takeIf(String::isNotEmpty),
    )
}

private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

private fun decode(value: String): String = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
