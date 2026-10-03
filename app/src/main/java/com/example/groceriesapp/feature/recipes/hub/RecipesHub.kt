package com.example.groceriesapp.feature.recipes.hub

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.recipes.CookEntry
import com.example.groceriesapp.data.recipes.CookTimeBucket
import com.example.groceriesapp.data.recipes.LibraryTab
import com.example.groceriesapp.data.recipes.RecentSource
import com.example.groceriesapp.data.recipes.Recipe
import com.example.groceriesapp.data.recipes.RecipeCollection
import com.example.groceriesapp.data.recipes.RecipeGenerationRequest
import com.example.groceriesapp.data.recipes.RecipeGenerationResult
import com.example.groceriesapp.data.recipes.RecipeMatcher
import com.example.groceriesapp.data.recipes.RecipeLibrary
import com.example.groceriesapp.data.recipes.RecipeLibraryQuery
import com.example.groceriesapp.data.recipes.RecipeReviewDraft
import com.example.groceriesapp.data.recipes.RecipeSort
import com.example.groceriesapp.data.recipes.RecipeSources
import java.io.File
import java.time.LocalDate

/** Everything the hub asks the app shell to do on its behalf. */
data class RecipesHubActions(
    val onOpenRecipe: (String) -> Unit,
    val onToggleFavorite: (String) -> Unit,
    val onWriteRecipe: () -> Unit,
    val onScanRecipeCamera: () -> Unit,
    val onScanRecipeGallery: () -> Unit,
    val onImportRecipeUrl: (String) -> Unit,
    val onGenerateIdeas: () -> Unit,
    val onGenerateRecipe: (request: String, photo: Bitmap?) -> Unit,
    val onGenerateRecipes: (RecipeGenerationRequest) -> Unit,
    val onClearLastGenerationId: () -> Unit,
    val onSaveReviewDraft: (Recipe) -> Unit,
    val onDiscardReviewDraft: () -> Unit,
    val onCreateCollection: (String) -> Unit,
    val onRenameCollection: (String, String) -> Unit,
    val onDeleteCollection: (String) -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * Host for the Recipes tab: routes between the home, library, filter/sort and import screens
 * according to [RecipesHubState]. Recipe detail and the editor remain app-level overlays owned
 * by the shell, so this composable only ever asks for them via [actions].
 */
@Composable
fun RecipesHub(
    state: RecipesHubState,
    inventory: List<InventoryItem>,
    recipes: List<Recipe>,
    favoriteIds: Set<String>,
    cookLog: List<CookEntry>,
    collections: List<RecipeCollection>,
    reviewDraft: RecipeReviewDraft?,
    recentSources: List<RecentSource>,
    generationHistory: List<RecipeGenerationResult>,
    lastGenerationId: String?,
    imagesDir: File,
    imageVersion: Int,
    aiEnabled: Boolean,
    aiBusy: Boolean,
    aiError: String?,
    slowOcrEngine: Boolean,
    actions: RecipesHubActions,
    modifier: Modifier = Modifier,
) {
    // Registered after the shell's own tab-level BackHandler, so it takes precedence while the
    // hub has somewhere to go back to; at HOME it is disabled and the shell's handler runs.
    BackHandler(enabled = state.canGoBack) { state.back() }

    val library = remember { RecipeLibrary() }
    val nowEpochMillis = remember(cookLog, recipes) { System.currentTimeMillis() }
    val today = remember(nowEpochMillis) { LocalDate.now() }
    var generateDialogOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    val matcher = remember { RecipeMatcher() }

    // A scan or link import that finished lands in the review step, wherever the user was.
    LaunchedEffect(reviewDraft) {
        if (reviewDraft != null && state.route != RecipesRoute.SCAN_REVIEW) {
            state.navigate(RecipesRoute.SCAN_REVIEW)
        }
    }

    // A finished structured generation opens its results once.
    LaunchedEffect(lastGenerationId) {
        if (lastGenerationId != null) {
            state.viewedGenerationId = lastGenerationId
            if (state.route != RecipesRoute.GENERATE_RESULTS) state.navigate(RecipesRoute.GENERATE_RESULTS)
            actions.onClearLastGenerationId()
        }
    }

    fun currentGenerationRequest() = RecipeGenerationRequest(
        selectedInventoryItemIds = state.generationSelectedIds,
        preferences = state.generationPreferences,
        time = state.generationTime,
    )

    when (state.route) {
        RecipesRoute.HOME -> RecipesHomeScreen(
            inventory = inventory,
            recipes = recipes,
            favoriteIds = favoriteIds,
            cookLog = cookLog,
            imagesDir = imagesDir,
            imageVersion = imageVersion,
            nowEpochMillis = nowEpochMillis,
            aiEnabled = aiEnabled,
            aiBusy = aiBusy,
            aiError = aiError,
            onOpenRecipe = actions.onOpenRecipe,
            onToggleFavorite = actions.onToggleFavorite,
            onOpenFavorites = {
                state.openLibrary(RecipeLibraryQuery(tab = LibraryTab.ALL, favoritesOnly = true))
            },
            onOpenExpiring = {
                state.openLibrary(
                    RecipeLibraryQuery(
                        tab = LibraryTab.ALL,
                        useExpiringFirst = true,
                        sort = RecipeSort.BEST_MATCH,
                    ),
                )
            },
            onOpenLongCooks = {
                state.openLibrary(
                    RecipeLibraryQuery(
                        tab = LibraryTab.ALL,
                        cookTimes = setOf(CookTimeBucket.FROM_30_TO_60, CookTimeBucket.OVER_60),
                        sort = RecipeSort.COOK_TIME_SHORTEST,
                    ),
                )
            },
            onGenerate = { state.navigate(RecipesRoute.GENERATE) },
            onGenerateIdeas = actions.onGenerateIdeas,
            onImport = { state.navigate(RecipesRoute.IMPORT_METHOD) },
            onWriteRecipe = actions.onWriteRecipe,
            onViewAllCooked = {
                state.openLibrary(
                    RecipeLibraryQuery(tab = LibraryTab.COOKED, sort = RecipeSort.RECENTLY_COOKED),
                )
            },
            onSeeAllRecommended = {
                state.openLibrary(
                    RecipeLibraryQuery(
                        tab = LibraryTab.ALL,
                        useExpiringFirst = true,
                        sort = RecipeSort.BEST_MATCH,
                    ),
                )
            },
            onOpenSettings = actions.onOpenSettings,
            modifier = modifier,
        )

        RecipesRoute.LIBRARY -> {
            val query = state.query
            val groups = remember(recipes, inventory, favoriteIds, cookLog, collections, today, query) {
                library.rows(recipes, inventory, favoriteIds, cookLog, collections, today, query)
            }
            RecipeLibraryScreen(
                query = query,
                groups = groups,
                collections = collections,
                nowEpochMillis = nowEpochMillis,
                imagesDir = imagesDir,
                imageVersion = imageVersion,
                searchRows = { text ->
                    library.rows(
                        recipes, inventory, favoriteIds, cookLog, collections, today,
                        query.copy(query = text),
                    ).flatMap { it.rows }
                },
                onQueryChange = { state.query = it },
                onOpenFilters = state::openFilters,
                onOpenSort = state::openSort,
                onOpenRecipe = actions.onOpenRecipe,
                onToggleFavorite = actions.onToggleFavorite,
                onCreateCollection = actions.onCreateCollection,
                onRenameCollection = actions.onRenameCollection,
                onDeleteCollection = actions.onDeleteCollection,
                onBack = { state.back() },
                modifier = modifier,
            )
        }

        RecipesRoute.FILTERS -> {
            val draft = state.draftQuery
            val count = remember(recipes, inventory, favoriteIds, cookLog, collections, today, draft) {
                library.count(recipes, inventory, favoriteIds, cookLog, collections, today, draft)
            }
            RecipeFiltersScreen(
                draft = draft,
                resultCount = count,
                inventory = inventory,
                onDraftChange = { state.draftQuery = it },
                onReset = { state.draftQuery = draft.withFiltersReset() },
                onApply = state::applyDraft,
                onBack = { state.back() },
                modifier = modifier,
            )
        }

        RecipesRoute.SORT -> RecipeSortScreen(
            sort = state.query.sort,
            organizeBy = state.query.organizeBy,
            onSelectSort = { sort ->
                state.query = state.query.copy(sort = sort)
                state.back()
            },
            onSelectOrganizeBy = { organizeBy ->
                state.query = state.query.copy(organizeBy = organizeBy)
                state.back()
            },
            onBack = { state.back() },
            modifier = modifier,
        )

        RecipesRoute.IMPORT_METHOD -> ImportMethodScreen(
            recentSources = recentSources,
            nowEpochMillis = nowEpochMillis,
            onScan = { state.navigate(RecipesRoute.SCAN) },
            onSources = { state.navigate(RecipesRoute.IMPORT_SOURCES) },
            onPasteLink = {
                state.importUrlPrefill = null
                state.navigate(RecipesRoute.IMPORT_URL)
            },
            onWriteRecipe = actions.onWriteRecipe,
            onOpenRecentSource = { source ->
                state.importUrlPrefill = RecipeSources.curated
                    .firstOrNull { it.host == source.host }?.homeUrl
                    ?: "https://${source.host}"
                state.navigate(RecipesRoute.IMPORT_URL)
            },
            onBack = { state.back() },
            modifier = modifier,
        )

        RecipesRoute.IMPORT_SOURCES -> ImportSourcesScreen(
            sites = RecipeSources.curated,
            onPick = { site ->
                state.importUrlPrefill = site.homeUrl
                state.navigate(RecipesRoute.IMPORT_URL)
            },
            onBack = { state.back() },
            modifier = modifier,
        )

        RecipesRoute.IMPORT_URL -> ImportUrlScreen(
            prefill = state.importUrlPrefill,
            siteHint = state.importUrlPrefill?.let(RecipeSources::matching),
            aiEnabled = aiEnabled,
            busy = aiBusy,
            error = aiError,
            onImport = actions.onImportRecipeUrl,
            onBack = { state.back() },
            modifier = modifier,
        )

        RecipesRoute.SCAN -> ScanRecipeScreen(
            busy = aiBusy,
            error = aiError,
            aiEnabled = aiEnabled,
            slowOcrEngine = slowOcrEngine,
            onTakePhoto = actions.onScanRecipeCamera,
            onPickPhoto = actions.onScanRecipeGallery,
            onBack = { state.back() },
            modifier = modifier,
        )

        RecipesRoute.SCAN_REVIEW -> {
            val draft = reviewDraft
            if (draft == null) {
                // Saved or discarded elsewhere; nothing left to review.
                LaunchedEffect(Unit) { state.back() }
            } else {
                ScanReviewScreen(
                    draft = draft,
                    onSave = { recipe ->
                        actions.onSaveReviewDraft(recipe)
                        state.popToHome()
                    },
                    onRetake = if (draft.imageFile != null) {
                        {
                            actions.onDiscardReviewDraft()
                            state.back()
                        }
                    } else {
                        null
                    },
                    onBack = {
                        actions.onDiscardReviewDraft()
                        state.back()
                    },
                    modifier = modifier,
                )
            }
        }

        RecipesRoute.GENERATE -> GenerateRecipeScreen(
            inventory = inventory,
            selectedIds = state.generationSelectedIds,
            preferences = state.generationPreferences,
            time = state.generationTime,
            aiEnabled = aiEnabled,
            aiBusy = aiBusy,
            aiError = aiError,
            hasHistory = generationHistory.isNotEmpty(),
            onSelectedChange = { state.generationSelectedIds = it },
            onPreferencesChange = { state.generationPreferences = it },
            onTimeChange = { state.generationTime = it },
            onGenerate = { actions.onGenerateRecipes(currentGenerationRequest()) },
            onDescribe = { generateDialogOpen = true },
            onHistory = { historyOpen = true },
            onOpenSettings = actions.onOpenSettings,
            onBack = { state.back() },
            modifier = modifier,
        )

        RecipesRoute.GENERATE_RESULTS -> {
            val result = generationHistory.firstOrNull { it.id == state.viewedGenerationId }
            if (result == null) {
                LaunchedEffect(Unit) { state.back() }
            } else {
                val byId = remember(recipes) { recipes.associateBy { it.id } }
                val matches = remember(result, byId, inventory, favoriteIds, today) {
                    result.recipeIds.mapNotNull { byId[it] }
                        .map { matcher.matchOne(it, inventory, today, favoriteIds) }
                }
                GenerateResultsScreen(
                    result = result,
                    matches = matches,
                    favoriteIds = favoriteIds,
                    imagesDir = imagesDir,
                    imageVersion = imageVersion,
                    aiBusy = aiBusy,
                    aiError = aiError,
                    onOpenRecipe = actions.onOpenRecipe,
                    onToggleFavorite = actions.onToggleFavorite,
                    onRegenerate = { actions.onGenerateRecipes(result.request) },
                    onBack = { state.back() },
                    modifier = modifier,
                )
            }
        }
    }

    if (historyOpen) {
        GenerationHistorySheet(
            history = generationHistory,
            availableRecipeIds = remember(recipes) { recipes.map { it.id }.toSet() },
            nowEpochMillis = nowEpochMillis,
            onOpen = { result ->
                historyOpen = false
                state.viewedGenerationId = result.id
                state.navigate(RecipesRoute.GENERATE_RESULTS)
            },
            onDismiss = { historyOpen = false },
        )
    }

    if (generateDialogOpen) {
        GenerateRecipeDialog(
            onGenerate = { request, photo ->
                generateDialogOpen = false
                actions.onGenerateRecipe(request, photo)
            },
            onDismiss = { generateDialogOpen = false },
        )
    }
}
