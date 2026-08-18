package com.example.groceriesapp.feature.recipes

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.core.content.FileProvider
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.recipes.Recipe
import com.example.groceriesapp.data.recipes.RecipeFilters
import com.example.groceriesapp.data.recipes.RecipeMatch
import com.example.groceriesapp.data.recipes.RecipeMatcher
import com.example.groceriesapp.data.recipes.RecipeSource
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.components.GroceriesSearchBar
import com.example.groceriesapp.ui.components.rememberGroceriesSearchState
import com.example.groceriesapp.ui.components.SectionHeader
import com.example.groceriesapp.ui.theme.spacing
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesScreen(
    inventory: List<InventoryItem>,
    curatedRecipes: List<Recipe>,
    userRecipes: List<Recipe>,
    generatedRecipes: List<Recipe>,
    favoriteIds: Set<String>,
    imagesDir: File,
    aiEnabled: Boolean,
    aiBusy: Boolean,
    aiError: String?,
    onOpenRecipe: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onWriteRecipe: () -> Unit,
    onScanRecipeCamera: () -> Unit,
    onScanRecipeGallery: () -> Unit,
    onImportRecipeUrl: (String) -> Unit,
    onGenerateIdeas: () -> Unit,
    onGenerateRecipe: (request: String, photo: Bitmap?) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchState = rememberGroceriesSearchState()
    val searchScope = rememberCoroutineScope()
    var useExpiringFirst by rememberSaveable { mutableStateOf(true) }
    var maxThirtyMinutes by rememberSaveable { mutableStateOf(false) }
    var onlyWhatIHave by rememberSaveable { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var urlDialogOpen by rememberSaveable { mutableStateOf(false) }
    var generateDialogOpen by rememberSaveable { mutableStateOf(false) }

    val allRecipes = remember(curatedRecipes, userRecipes, generatedRecipes) {
        userRecipes + generatedRecipes + curatedRecipes
    }
    // The chips drive the main list; the query only narrows the search view, so the matcher runs
    // twice against the same chip state rather than the list re-sorting behind the overlay.
    val filters = RecipeFilters(
        useExpiringFirst = useExpiringFirst,
        maxPrepMinutes = if (maxThirtyMinutes) 30 else null,
        onlyWhatIHave = onlyWhatIHave,
        favoritesOnly = favoritesOnly,
    )
    val sections = remember(allRecipes, inventory, filters, favoriteIds) {
        RecipeMatcher().sections(
            recipes = allRecipes,
            inventory = inventory,
            today = LocalDate.now(),
            filters = filters,
            favoriteIds = favoriteIds,
        )
    }

    val searchQuery = searchState.query
    val searchResults = remember(allRecipes, inventory, filters, favoriteIds, searchQuery) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            RecipeMatcher().sections(
                recipes = allRecipes,
                inventory = inventory,
                today = LocalDate.now(),
                filters = filters.copy(query = searchQuery),
                favoriteIds = favoriteIds,
            ).let { it.bestMatches + it.moreIdeas }
        }
    }

    // Pinned rather than collapsing: the bar carries the search/filter actions, so it
    // stays put and only picks up the scrolled container colour that marks the boundary.
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
                        // Not gated on aiEnabled: pages carrying schema.org markup import
                        // without any AI call, and the dialog explains the key-less limitation.
                        DropdownMenuItem(
                            text = { Text("Import from link") },
                            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                            onClick = {
                                addMenuOpen = false
                                urlDialogOpen = true
                            },
                        )
                        if (aiEnabled) {
                            // Asks for one specific recipe, unlike the "Get AI ideas" link on the
                            // More ideas header, which shotguns suggestions from the inventory.
                            DropdownMenuItem(
                                text = { Text("Generate with AI") },
                                enabled = !aiBusy,
                                leadingIcon = {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                },
                                onClick = {
                                    addMenuOpen = false
                                    generateDialogOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Photograph recipe") },
                                leadingIcon = {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                                },
                                onClick = {
                                    addMenuOpen = false
                                    onScanRecipeCamera()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Import recipe photo") },
                                leadingIcon = {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                                },
                                onClick = {
                                    addMenuOpen = false
                                    onScanRecipeGallery()
                                },
                            )
                        }
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
                bottom = MaterialTheme.spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item(key = "filters") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        selected = useExpiringFirst,
                        onClick = { useExpiringFirst = !useExpiringFirst },
                        label = { Text("Use expiring first") },
                        leadingIcon = {
                            if (useExpiringFirst) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                    FilterChip(
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        selected = maxThirtyMinutes,
                        onClick = { maxThirtyMinutes = !maxThirtyMinutes },
                        label = { Text("< 30 min") },
                    )
                    FilterChip(
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        selected = onlyWhatIHave,
                        onClick = { onlyWhatIHave = !onlyWhatIHave },
                        label = { Text("Only what I have") },
                    )
                    FilterChip(
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        selected = favoritesOnly,
                        onClick = { favoritesOnly = !favoritesOnly },
                        label = { Text("Saved") },
                        leadingIcon = {
                            if (favoritesOnly) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                }
            }

            if (sections.bestMatches.isEmpty() && sections.moreIdeas.isEmpty()) {
                item(key = "empty") {
                    EmptyStateCard(
                        title = "No matching recipes",
                        body = if (allRecipes.isEmpty()) {
                            "Add your first recipe to get cooking suggestions from your inventory."
                        } else {
                            "Try removing a filter, or add your own recipe."
                        },
                        actionLabel = "Write recipe",
                        onAction = onWriteRecipe,
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                    )
                }
            }

            if (sections.bestMatches.isNotEmpty()) {
                item(key = "best-header") {
                    SectionHeader(title = "Best matches")
                }
                items(
                    count = sections.bestMatches.size,
                    key = { "best-" + sections.bestMatches[it].recipe.id },
                ) { index ->
                    val match = sections.bestMatches[index]
                    BestMatchCard(
                        match = match,
                        favorite = match.recipe.id in favoriteIds,
                        imagesDir = imagesDir,
                        onOpen = { onOpenRecipe(match.recipe.id) },
                        onToggleFavorite = { onToggleFavorite(match.recipe.id) },
                    )
                }
            }

            if (sections.moreIdeas.isNotEmpty() || aiEnabled) {
                item(key = "ideas-header") {
                    SectionHeader(
                        title = "More ideas",
                        actionLabel = if (aiEnabled && !aiBusy) "Get AI ideas" else null,
                        onAction = if (aiEnabled && !aiBusy) onGenerateIdeas else null,
                    )
                }
            }
            if (aiBusy) {
                item(key = "ai-busy") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            "Asking the kitchen AI for ideas…",
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
            items(
                count = sections.moreIdeas.size,
                key = { "idea-" + sections.moreIdeas[it].recipe.id },
            ) { index ->
                val match = sections.moreIdeas[index]
                MoreIdeaRow(
                    match = match,
                    favorite = match.recipe.id in favoriteIds,
                    imagesDir = imagesDir,
                    onOpen = { onOpenRecipe(match.recipe.id) },
                    onToggleFavorite = { onToggleFavorite(match.recipe.id) },
                )
            }
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
                    onOpen = {
                        searchScope.launch { searchState.collapse() }
                        onOpenRecipe(match.recipe.id)
                    },
                    onToggleFavorite = { onToggleFavorite(match.recipe.id) },
                )
            }
        }
    }

    if (urlDialogOpen) {
        ImportFromUrlDialog(
            aiEnabled = aiEnabled,
            onImport = { url ->
                urlDialogOpen = false
                onImportRecipeUrl(url)
            },
            onDismiss = { urlDialogOpen = false },
        )
    }

    if (generateDialogOpen) {
        GenerateRecipeDialog(
            onGenerate = { request, photo ->
                generateDialogOpen = false
                onGenerateRecipe(request, photo)
            },
            onDismiss = { generateDialogOpen = false },
        )
    }
}

/**
 * Asks for one specific recipe: a description, a photo, or both. A photo alone is a valid brief —
 * it may be a dish to recreate, ingredients on the counter, or a handwritten card — so the confirm
 * button unlocks on either input.
 */
@Composable
private fun GenerateRecipeDialog(
    onGenerate: (String, Bitmap?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var request by rememberSaveable { mutableStateOf("") }
    // Bitmaps are far too large for saved instance state; a process death during the camera round
    // trip loses the photo, and the dialog reopens with just the typed text.
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { photo = decodeOrientedBitmap(context, it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = cameraFile
        cameraFile = null
        if (success && file != null) photo = decodeOrientedBitmap(file)
        file?.delete()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
        title = { Text("Generate a recipe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                Text(
                    "Describe the dish you want, add a photo of it, or both. You get one " +
                        "recipe back, using your inventory where it fits.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = request,
                    onValueChange = { request = it },
                    label = { Text("What should it be?") },
                    placeholder = { Text("a quick vegan pasta for two") },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier.fillMaxWidth(),
                )
                val currentPhoto = photo
                if (currentPhoto != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        Image(
                            bitmap = currentPhoto.asImageBitmap(),
                            contentDescription = "Attached photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(MaterialTheme.shapes.small),
                        )
                        Text(
                            "Photo attached",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { photo = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove photo")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                        TextButton(
                            onClick = {
                                val directory = File(context.cacheDir, "camera").apply(File::mkdirs)
                                val file = File(directory, "recipe_prompt_${UUID.randomUUID()}.jpg")
                                cameraFile = file
                                cameraLauncher.launch(
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.files",
                                        file,
                                    ),
                                )
                            },
                        ) {
                            Icon(
                                Icons.Default.PhotoCamera,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(MaterialTheme.spacing.extraSmall))
                            Text("Take photo")
                        }
                        TextButton(
                            onClick = {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                        ) {
                            Icon(
                                Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(MaterialTheme.spacing.extraSmall))
                            Text("Add photo")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onGenerate(request, photo) },
                enabled = request.isNotBlank() || photo != null,
            ) {
                Text("Generate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Paste-a-link dialog for recipe import. Kept a plain [OutlinedTextField] rather than anything
 * search-shaped: this is a form field whose value the user must be able to read back and correct.
 */
@Composable
private fun ImportFromUrlDialog(
    aiEnabled: Boolean,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                Text(
                    if (aiEnabled) {
                        "Paste a recipe link. Most cooking sites publish structured recipe " +
                            "data, which is read directly; anything else is handled by AI."
                    } else {
                        "Paste a recipe link. Most cooking sites publish structured recipe " +
                            "data, which is read directly — no API key needed. Pages without it " +
                            "need an OpenAI key in Settings."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Recipe link") },
                    placeholder = { Text("example.com/pasta") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { if (url.isNotBlank()) onImport(url) },
                    ),
                    trailingIcon = {
                        if (url.isNotEmpty()) {
                            IconButton(onClick = { url = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear link")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(url) }, enabled = url.isNotBlank()) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** The green "You have 100%" pill from the mockup; softer container color below full coverage. */
@Composable
private fun CoveragePill(coverage: Double, modifier: Modifier = Modifier) {
    val percent = (coverage * 100).roundToInt()
    val full = percent >= 100
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (full) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (full) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = "You have $percent%",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.small,
                vertical = MaterialTheme.spacing.extraSmall / 2,
            ),
        )
    }
}

@Composable
private fun ExpiringPill(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = if (count == 1) "Uses 1 expiring" else "Uses $count expiring",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.small,
                vertical = MaterialTheme.spacing.extraSmall / 2,
            ),
        )
    }
}

/**
 * Bookmark toggle for a recipe. Saving is a "come back to this" gesture rather than a rating,
 * so it reads as a bookmark; the underlying flag is still the repository's favourite set.
 */
@Composable
private fun SaveButton(
    saved: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
            contentDescription = if (saved) "Remove from saved" else "Save recipe",
            tint = if (saved) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Fixed-width pill: it never wraps or shrinks, so callers must give the title the flexible slot. */
@Composable
private fun AiTag(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.wrapContentWidth(unbounded = true),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.small,
                vertical = MaterialTheme.spacing.extraSmall / 2,
            ),
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Text(
                "AI",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun BestMatchCard(
    match: RecipeMatch,
    favorite: Boolean,
    imagesDir: File,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        RecipeImage(
            recipe = match.recipe,
            imagesDir = imagesDir,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            iconSize = 40.dp,
        )
        Column(
            modifier = Modifier.padding(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.extraSmall,
                top = MaterialTheme.spacing.small,
                bottom = MaterialTheme.spacing.small,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            // The bookmark sits in the title row rather than floating over the photo: it stays
            // legible whatever the image is, and matches the trailing toggle on the idea rows.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = match.recipe.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (match.recipe.source == RecipeSource.AI) {
                    AiTag(modifier = Modifier.padding(end = MaterialTheme.spacing.extraSmall))
                }
                SaveButton(saved = favorite, onToggle = onToggleFavorite)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                modifier = Modifier.padding(bottom = MaterialTheme.spacing.extraSmall),
            ) {
                Text(
                    text = "${match.recipe.prepMinutes} min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CoveragePill(coverage = match.coverage)
                if (match.expiringUsed > 0) {
                    ExpiringPill(count = match.expiringUsed)
                }
            }
        }
    }
}

@Composable
private fun MoreIdeaRow(
    match: RecipeMatch,
    favorite: Boolean,
    imagesDir: File,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            modifier = Modifier.padding(
                start = MaterialTheme.spacing.small,
                end = MaterialTheme.spacing.extraSmall,
                top = MaterialTheme.spacing.small,
                bottom = MaterialTheme.spacing.small,
            ),
        ) {
            RecipeImage(
                recipe = match.recipe,
                imagesDir = imagesDir,
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.medium),
                iconSize = 22.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall / 2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    // Weighted so the title yields space instead of taking it: unweighted
                    // children are measured first, which left a long title squeezing the tag
                    // down to a two-letter stack.
                    Text(
                        text = match.recipe.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (match.recipe.source == RecipeSource.AI) {
                        AiTag()
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    Text(
                        text = "${match.recipe.prepMinutes} min · You have " +
                            "${(match.coverage * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (match.expiringUsed > 0) {
                        ExpiringPill(count = match.expiringUsed)
                    }
                }
            }
            SaveButton(saved = favorite, onToggle = onToggleFavorite)
        }
    }
}
