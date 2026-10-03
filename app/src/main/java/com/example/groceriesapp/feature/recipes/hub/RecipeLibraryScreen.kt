package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.recipes.LibraryGroup
import com.example.groceriesapp.data.recipes.LibraryRow
import com.example.groceriesapp.data.recipes.LibraryTab
import com.example.groceriesapp.data.recipes.RecipeCollection
import com.example.groceriesapp.data.recipes.RecipeLibraryQuery
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.components.GroceriesSearchBar
import com.example.groceriesapp.ui.components.SectionHeader
import com.example.groceriesapp.ui.components.SettingsChevron
import com.example.groceriesapp.ui.components.SettingsGroup
import com.example.groceriesapp.ui.components.SettingsRow
import com.example.groceriesapp.ui.components.SettingsRowDivider
import com.example.groceriesapp.ui.components.rememberGroceriesSearchState
import com.example.groceriesapp.ui.theme.spacing
import java.io.File
import kotlinx.coroutines.launch

/**
 * "My Recipes": the saved library with All / Saved / Cooked / Collections tabs, a Filters button
 * carrying its active-filter count, and the current sort. The rows themselves come precomputed
 * from [com.example.groceriesapp.data.recipes.RecipeLibrary] so this screen only lays them out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeLibraryScreen(
    query: RecipeLibraryQuery,
    groups: List<LibraryGroup>,
    collections: List<RecipeCollection>,
    nowEpochMillis: Long,
    imagesDir: File,
    imageVersion: Int,
    searchRows: (String) -> List<LibraryRow>,
    onQueryChange: (RecipeLibraryQuery) -> Unit,
    onOpenFilters: () -> Unit,
    onOpenSort: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onCreateCollection: (String) -> Unit,
    onRenameCollection: (String, String) -> Unit,
    onDeleteCollection: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchState = rememberGroceriesSearchState()
    val searchScope = rememberCoroutineScope()
    var newCollectionOpen by rememberSaveable { mutableStateOf(false) }
    var renameCollectionId by rememberSaveable { mutableStateOf<String?>(null) }
    val rowCount = groups.sumOf { it.rows.size }
    val activeCollection = query.collectionId?.let { id -> collections.firstOrNull { it.id == id } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("My Recipes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { searchScope.launch { searchState.expand() } }) {
                        Icon(Icons.Default.Search, contentDescription = "Search recipes")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PrimaryTabRow(selectedTabIndex = query.tab.ordinal) {
                LibraryTab.entries.forEach { tab ->
                    Tab(
                        selected = query.tab == tab,
                        onClick = { onQueryChange(query.copy(tab = tab, collectionId = null)) },
                        text = { Text(tab.label) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.small,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = onOpenFilters,
                    label = { Text("Filters") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                    trailingIcon = if (query.activeFilterCount > 0) {
                        { Badge { Text("${query.activeFilterCount}") } }
                    } else {
                        null
                    },
                )
                AssistChip(
                    onClick = onOpenSort,
                    label = {
                        Column {
                            Text(
                                "Sort by",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(query.sort.label)
                        }
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }

            if (query.tab == LibraryTab.COLLECTIONS && activeCollection == null) {
                CollectionsList(
                    collections = collections,
                    onOpen = { onQueryChange(query.copy(collectionId = it.id)) },
                    onNew = { newCollectionOpen = true },
                    onRename = { renameCollectionId = it.id },
                    onDelete = { onDeleteCollection(it.id) },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = MaterialTheme.spacing.medium,
                        end = MaterialTheme.spacing.medium,
                        bottom = MaterialTheme.spacing.large,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    if (activeCollection != null) {
                        item(key = "collection-header") {
                            SectionHeader(
                                title = activeCollection.name,
                                actionLabel = "All collections",
                                onAction = { onQueryChange(query.copy(collectionId = null)) },
                            )
                        }
                    }
                    if (rowCount == 0) {
                        item(key = "empty") {
                            EmptyStateCard(
                                title = emptyTitle(query),
                                body = emptyBody(query),
                                icon = Icons.AutoMirrored.Filled.MenuBook,
                                modifier = Modifier.padding(top = MaterialTheme.spacing.medium),
                            )
                        }
                    }
                    groups.forEach { group ->
                        if (group.title != null) {
                            item(key = "group-" + group.title) {
                                SectionHeader(
                                    title = group.title,
                                    modifier = Modifier.padding(top = MaterialTheme.spacing.small),
                                )
                            }
                        }
                        items(group.rows, key = { (group.title ?: "") + "/" + it.recipe.id }) { row ->
                            RecipeLibraryRow(
                                row = row,
                                nowEpochMillis = nowEpochMillis,
                                imagesDir = imagesDir,
                                imageVersion = imageVersion,
                                onOpen = { onOpenRecipe(row.recipe.id) },
                                onToggleFavorite = { onToggleFavorite(row.recipe.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    GroceriesSearchBar(
        state = searchState,
        placeholder = "Search your recipes",
    ) {
        val text = searchState.query
        val results = remember(text) { if (text.isBlank()) emptyList() else searchRows(text) }
        LazyColumn(
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            items(results, key = { "search-" + it.recipe.id }) { row ->
                RecipeLibraryRow(
                    row = row,
                    nowEpochMillis = nowEpochMillis,
                    imagesDir = imagesDir,
                    imageVersion = imageVersion,
                    onOpen = {
                        searchScope.launch { searchState.collapse() }
                        onOpenRecipe(row.recipe.id)
                    },
                    onToggleFavorite = { onToggleFavorite(row.recipe.id) },
                )
            }
        }
    }

    if (newCollectionOpen) {
        CollectionNameDialog(
            title = "New collection",
            initial = "",
            onConfirm = { name ->
                newCollectionOpen = false
                onCreateCollection(name)
            },
            onDismiss = { newCollectionOpen = false },
        )
    }
    val renaming = renameCollectionId?.let { id -> collections.firstOrNull { it.id == id } }
    if (renaming != null) {
        CollectionNameDialog(
            title = "Rename collection",
            initial = renaming.name,
            onConfirm = { name ->
                renameCollectionId = null
                onRenameCollection(renaming.id, name)
            },
            onDismiss = { renameCollectionId = null },
        )
    }
}

@Composable
private fun CollectionsList(
    collections: List<RecipeCollection>,
    onOpen: (RecipeCollection) -> Unit,
    onNew: () -> Unit,
    onRename: (RecipeCollection) -> Unit,
    onDelete: (RecipeCollection) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MaterialTheme.spacing.medium,
            end = MaterialTheme.spacing.medium,
            bottom = MaterialTheme.spacing.large,
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        if (collections.isEmpty()) {
            item(key = "empty") {
                EmptyStateCard(
                    title = "No collections yet",
                    body = "Group recipes into collections such as “Weeknight dinners” or “High protein”.",
                    actionLabel = "New collection",
                    onAction = onNew,
                    icon = Icons.Default.Folder,
                )
            }
        } else {
            item(key = "collections") {
                SettingsGroup {
                    collections.forEachIndexed { index, collection ->
                        if (index > 0) SettingsRowDivider(inset = 56.dp)
                        var menuOpen by remember(collection.id) { mutableStateOf(false) }
                        SettingsRow(
                            headline = collection.name,
                            supportingText = if (collection.recipeIds.size == 1) {
                                "1 recipe"
                            } else {
                                "${collection.recipeIds.size} recipes"
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Collection options")
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                onRename(collection)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                onDelete(collection)
                                            },
                                        )
                                    }
                                    SettingsChevron()
                                }
                            },
                            onClick = { onOpen(collection) },
                        )
                    }
                }
            }
            item(key = "new") {
                Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("New collection", modifier = Modifier.padding(start = MaterialTheme.spacing.small))
                }
            }
        }
    }
}

@Composable
private fun CollectionNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Weeknight dinners") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun emptyTitle(query: RecipeLibraryQuery): String = when {
    query.activeFilterCount > 0 -> "No recipes match"
    query.tab == LibraryTab.COOKED -> "Nothing cooked yet"
    query.tab == LibraryTab.SAVED -> "No saved recipes"
    query.tab == LibraryTab.COLLECTIONS -> "This collection is empty"
    else -> "No recipes"
}

private fun emptyBody(query: RecipeLibraryQuery): String = when {
    query.activeFilterCount > 0 -> "Try removing a filter."
    query.tab == LibraryTab.COOKED -> "Tap “I cooked this” on a recipe and it shows up here with the date."
    query.tab == LibraryTab.SAVED -> "Bookmark a recipe, write your own, or import one to build your library."
    query.tab == LibraryTab.COLLECTIONS -> "Add recipes from their detail page."
    else -> "Add your first recipe."
}
