package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.recipes.CookEntry
import com.example.groceriesapp.data.recipes.Recipe
import com.example.groceriesapp.data.recipes.RecipeFilters
import com.example.groceriesapp.data.recipes.RecipeLibrary
import com.example.groceriesapp.data.recipes.RecipeMatch
import com.example.groceriesapp.data.recipes.RecipeMatcher
import com.example.groceriesapp.data.recipes.RecipeStats
import com.example.groceriesapp.ui.components.ActionTile
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.components.GroceriesSearchBar
import com.example.groceriesapp.ui.components.HeroActionCard
import com.example.groceriesapp.ui.components.SectionHeader
import com.example.groceriesapp.ui.components.rememberGroceriesSearchState
import com.example.groceriesapp.ui.theme.spacing
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * The Recipes tab's landing screen: quick entry points, the two headline actions (generate,
 * import), what was cooked recently, and inventory-driven recommendations. Everything deeper —
 * the full library, filters, import flows — is one tap away rather than stacked on this page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipesHomeScreen(
    inventory: List<InventoryItem>,
    recipes: List<Recipe>,
    favoriteIds: Set<String>,
    cookLog: List<CookEntry>,
    imagesDir: File,
    imageVersion: Int,
    nowEpochMillis: Long,
    aiEnabled: Boolean,
    aiBusy: Boolean,
    aiError: String?,
    onOpenRecipe: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenExpiring: () -> Unit,
    onOpenLongCooks: () -> Unit,
    onGenerate: () -> Unit,
    onGenerateIdeas: () -> Unit,
    onImport: () -> Unit,
    onWriteRecipe: () -> Unit,
    onViewAllCooked: () -> Unit,
    onSeeAllRecommended: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchState = rememberGroceriesSearchState()
    val searchScope = rememberCoroutineScope()
    var addMenuOpen by remember { mutableStateOf(false) }
    val library = remember { RecipeLibrary() }
    val today = remember(nowEpochMillis) { LocalDate.now() }

    val counts = remember(recipes, inventory, favoriteIds, today) {
        library.homeCounts(recipes, inventory, favoriteIds, today)
    }
    val stats = remember(cookLog) { library.statsFor(cookLog) }
    val recentlyCooked = remember(recipes, cookLog) { library.recentlyCooked(recipes, cookLog) }
    val recommended = remember(recipes, inventory, favoriteIds, today) {
        val sections = RecipeMatcher().sections(
            recipes = recipes,
            inventory = inventory,
            today = today,
            filters = RecipeFilters(useExpiringFirst = true),
            favoriteIds = favoriteIds,
        )
        (sections.bestMatches + sections.moreIdeas).take(RECOMMENDED_LIMIT)
    }

    val searchQuery = searchState.query
    val searchResults = remember(recipes, inventory, favoriteIds, searchQuery, today) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            RecipeMatcher().sections(
                recipes = recipes,
                inventory = inventory,
                today = today,
                filters = RecipeFilters(useExpiringFirst = true, query = searchQuery),
                favoriteIds = favoriteIds,
            ).let { it.bestMatches + it.moreIdeas }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Recipes") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { searchScope.launch { searchState.expand() } }) {
                        Icon(Icons.Default.Search, contentDescription = "Search recipes")
                    }
                    IconButton(onClick = { addMenuOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add recipe")
                    }
                    DropdownMenu(
                        expanded = addMenuOpen,
                        onDismissRequest = { addMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Write recipe") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                addMenuOpen = false
                                onWriteRecipe()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Import recipe") },
                            leadingIcon = {
                                Icon(Icons.Default.FileDownload, contentDescription = null)
                            },
                            onClick = {
                                addMenuOpen = false
                                onImport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Generate with AI") },
                            leadingIcon = {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            },
                            onClick = {
                                addMenuOpen = false
                                onGenerate()
                            },
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.large,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item(key = "prompt") {
                Text(
                    text = "What would you like to do?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "tiles") {
                // Intrinsic height so a wrapping title on one tile stretches its siblings too.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    modifier = Modifier.height(IntrinsicSize.Max),
                ) {
                    ActionTile(
                        title = "Favorites",
                        subtitle = recipeCount(counts.favorites),
                        icon = Icons.Default.Favorite,
                        onClick = onOpenFavorites,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    ActionTile(
                        title = "Use expiring first",
                        subtitle = recipeCount(counts.expiringMatches),
                        icon = Icons.Default.History,
                        onClick = onOpenExpiring,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    ActionTile(
                        title = "> 30 min",
                        subtitle = recipeCount(counts.over30Minutes),
                        icon = Icons.Default.Timer,
                        onClick = onOpenLongCooks,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            item(key = "generate") {
                HeroActionCard(
                    title = "Generate with AI",
                    subtitle = if (aiEnabled) {
                        "Get recipe ideas from what you have"
                    } else {
                        "Add an OpenAI key in Settings to enable"
                    },
                    icon = Icons.Default.AutoAwesome,
                    highlighted = true,
                    onClick = onGenerate,
                )
            }
            item(key = "import") {
                HeroActionCard(
                    title = "Import recipe",
                    subtitle = "From a photo, a link, or cooking sites",
                    icon = Icons.Default.FileDownload,
                    onClick = onImport,
                )
            }
            if (aiBusy) {
                item(key = "ai-busy") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            "Asking the kitchen AI…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (aiError != null) {
                item(key = "ai-error") {
                    Text(
                        aiError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (recentlyCooked.isNotEmpty()) {
                item(key = "cooked-header") {
                    SectionHeader(
                        title = "Cooked by you",
                        actionLabel = "View all",
                        onAction = onViewAllCooked,
                    )
                }
                item(key = "cooked-row") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        contentPadding = PaddingValues(end = MaterialTheme.spacing.small),
                    ) {
                        items(recentlyCooked, key = { "cooked-" + it.first.id }) { (recipe, entry) ->
                            CookedRecipeCard(
                                recipe = recipe,
                                lastCook = entry,
                                stats = stats[recipe.id] ?: RecipeStats.NONE,
                                nowEpochMillis = nowEpochMillis,
                                imagesDir = imagesDir,
                                imageVersion = imageVersion,
                                onClick = { onOpenRecipe(recipe.id) },
                            )
                        }
                    }
                }
            }

            item(key = "recommended-header") {
                SectionHeader(
                    title = "Recommended for you",
                    actionLabel = if (recommended.isNotEmpty()) "See all" else null,
                    onAction = if (recommended.isNotEmpty()) onSeeAllRecommended else null,
                )
            }
            if (recommended.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(
                        title = "Nothing to recommend yet",
                        body = if (recipes.isEmpty()) {
                            "Add a recipe and stock your inventory to get suggestions."
                        } else {
                            "Add items to your inventory to see what you can cook."
                        },
                        actionLabel = "Write recipe",
                        onAction = onWriteRecipe,
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                    )
                }
            } else {
                items(recommended, key = { "rec-" + it.recipe.id }) { match ->
                    RecommendedRow(
                        match = match,
                        stats = stats[match.recipe.id],
                        favorite = match.recipe.id in favoriteIds,
                        imagesDir = imagesDir,
                        imageVersion = imageVersion,
                        onOpen = { onOpenRecipe(match.recipe.id) },
                        onToggleFavorite = { onToggleFavorite(match.recipe.id) },
                    )
                }
                if (aiEnabled) {
                    item(key = "ai-ideas") {
                        SectionHeader(
                            title = "",
                            actionLabel = if (!aiBusy) "Get more ideas with AI" else null,
                            onAction = if (!aiBusy) onGenerateIdeas else null,
                        )
                    }
                }
            }
            item(key = "bottom-space") { Column(Modifier.height(MaterialTheme.spacing.small)) {} }
        }
    }

    GroceriesSearchBar(
        state = searchState,
        placeholder = "Search recipes or ingredients",
    ) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            items(searchResults, key = { "search-" + it.recipe.id }) { match ->
                MoreIdeaRow(
                    match = match,
                    favorite = match.recipe.id in favoriteIds,
                    imagesDir = imagesDir,
                    imageVersion = imageVersion,
                    onOpen = {
                        searchScope.launch { searchState.collapse() }
                        onOpenRecipe(match.recipe.id)
                    },
                    onToggleFavorite = { onToggleFavorite(match.recipe.id) },
                )
            }
        }
    }
}

/** A recommendation row: the idea row plus the recipe's stars when it has been rated. */
@Composable
private fun RecommendedRow(
    match: RecipeMatch,
    stats: RecipeStats?,
    favorite: Boolean,
    imagesDir: File,
    imageVersion: Int,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Column {
        MoreIdeaRow(
            match = match,
            favorite = favorite,
            imagesDir = imagesDir,
            imageVersion = imageVersion,
            onOpen = onOpen,
            onToggleFavorite = onToggleFavorite,
            rating = stats?.averageRating,
        )
    }
}

private fun recipeCount(count: Int): String =
    if (count == 1) "1 recipe" else "$count recipes"

private const val RECOMMENDED_LIMIT = 5
