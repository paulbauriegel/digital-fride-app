package com.example.groceriesapp.feature.recipes

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.groceriesapp.data.recipes.CookConsumption
import com.example.groceriesapp.data.recipes.IngredientMatch
import com.example.groceriesapp.data.recipes.IngredientStatus
import com.example.groceriesapp.data.recipes.RecipeMatch
import com.example.groceriesapp.data.recipes.RecipeCollection
import com.example.groceriesapp.data.recipes.RecipeQaTurn
import com.example.groceriesapp.data.recipes.RecipeSource
import com.example.groceriesapp.data.recipes.RecipeStats
import com.example.groceriesapp.data.recipes.formatRelativeDays
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.feature.recipes.hub.AddToCollectionSheet
import com.example.groceriesapp.ui.components.SectionHeader
import com.example.groceriesapp.ui.components.StarRating
import com.example.groceriesapp.ui.expiryUrgency
import com.example.groceriesapp.ui.formatQuantity
import com.example.groceriesapp.ui.theme.spacing
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    match: RecipeMatch,
    favorite: Boolean,
    stats: RecipeStats,
    nowEpochMillis: Long,
    imagesDir: File,
    imageVersion: Int,
    aiEnabled: Boolean,
    aiBusy: Boolean,
    aiError: String?,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCooked: (consumptions: List<CookConsumption>, rating: Int?) -> Unit,
    onPhotoPicked: (Bitmap) -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onAdaptToInventory: (() -> Unit)?,
    onGeneratePhoto: (() -> Unit)?,
    onAskQuestion: (suspend (question: String, history: List<RecipeQaTurn>) -> String)?,
    collections: List<RecipeCollection>,
    onSetInCollection: (collectionId: String, included: Boolean) -> Unit,
    onCreateCollection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = match.recipe
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showCookSheet by remember { mutableStateOf(false) }
    var showQaSheet by remember { mutableStateOf(false) }
    var showCollectionSheet by remember { mutableStateOf(false) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { decodeOrientedBitmap(context, it) }?.let(onPhotoPicked)
    }
    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) {
            decodeOrientedBitmap(file)?.let(onPhotoPicked)
        }
        file?.delete()
    }

    fun launchCamera() {
        val cameraDir = File(context.cacheDir, "camera").apply(File::mkdirs)
        val file = File(cameraDir, "recipe_${UUID.randomUUID()}.jpg")
        pendingCameraFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        takePictureLauncher.launch(uri)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (favorite) {
                                Icons.Default.Bookmark
                            } else {
                                Icons.Default.BookmarkBorder
                            },
                            contentDescription = if (favorite) {
                                "Remove from saved"
                            } else {
                                "Save recipe"
                            },
                            tint = if (favorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                val count = collections.count { recipe.id in it.recipeIds }
                                Text(if (count == 0) "Add to collection" else "Collections ($count)")
                            },
                            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                showCollectionSheet = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Take photo") },
                            leadingIcon = {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                launchCamera()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Choose photo") },
                            leadingIcon = {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                pickImageLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                        )
                        if (onGeneratePhoto != null && aiEnabled) {
                            DropdownMenuItem(
                                text = { Text("Generate photo") },
                                leadingIcon = {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onGeneratePhoto()
                                },
                            )
                        }
                        if (onEdit != null) {
                            DropdownMenuItem(
                                text = { Text("Edit recipe") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                },
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("Delete recipe") },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    confirmDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MaterialTheme.spacing.medium,
                    end = MaterialTheme.spacing.medium,
                    bottom = MaterialTheme.spacing.threeExtraLarge,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            RecipeImage(
                recipe = recipe,
                imagesDir = imagesDir,
                imageVersion = imageVersion,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                iconSize = 48.dp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    Text(
                        text = recipe.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = buildString {
                        append("${recipe.prepMinutes} min · serves ${recipe.servings}")
                        if (recipe.source == RecipeSource.AI) append(" · AI generated")
                        if (recipe.source == RecipeSource.USER) append(" · your recipe")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (stats.timesCooked > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        StarRating(rating = stats.averageRating)
                        Text(
                            text = buildString {
                                append(if (stats.timesCooked == 1) "Cooked once" else "Cooked ${stats.timesCooked}×")
                                stats.lastCookedAtEpochMillis?.let { last ->
                                    append(" · ")
                                    append(formatRelativeDays(last, nowEpochMillis).replaceFirstChar(Char::lowercase))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (aiBusy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        "Working on it…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (aiError != null) {
                Text(
                    aiError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionHeader(title = "Ingredients")
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                match.ingredientMatches.forEach { ingredientMatch ->
                    IngredientRow(ingredientMatch)
                }
            }

            SectionHeader(title = "Steps")
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                recipe.steps.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(
                                    horizontal = MaterialTheme.spacing.small,
                                    vertical = MaterialTheme.spacing.extraSmall / 2,
                                ),
                            )
                        }
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Button(
                onClick = { showCookSheet = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MaterialTheme.spacing.small),
            ) {
                Icon(Icons.Default.Restaurant, contentDescription = null)
                Text(
                    "I cooked this",
                    modifier = Modifier.padding(start = MaterialTheme.spacing.small),
                )
            }
            if (onAdaptToInventory != null && aiEnabled) {
                OutlinedButton(
                    onClick = onAdaptToInventory,
                    enabled = !aiBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Text(
                        "Adapt to what I have",
                        modifier = Modifier.padding(start = MaterialTheme.spacing.small),
                    )
                }
            }
            if (onAskQuestion != null && aiEnabled) {
                OutlinedButton(
                    onClick = { showQaSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.QuestionAnswer, contentDescription = null)
                    Text(
                        "Ask a question",
                        modifier = Modifier.padding(start = MaterialTheme.spacing.small),
                    )
                }
            }
        }
    }

    if (showCollectionSheet) {
        AddToCollectionSheet(
            recipeId = recipe.id,
            collections = collections,
            onSetInCollection = onSetInCollection,
            onCreateCollection = onCreateCollection,
            onDismiss = { showCollectionSheet = false },
        )
    }

    if (showQaSheet && onAskQuestion != null) {
        RecipeQaSheet(
            recipeTitle = recipe.title,
            onAskQuestion = onAskQuestion,
            onDismiss = { showQaSheet = false },
        )
    }

    if (showCookSheet) {
        CookRecipeSheet(
            match = match,
            onDismiss = { showCookSheet = false },
            onConfirm = { consumptions, rating ->
                showCookSheet = false
                onCooked(consumptions, rating)
            },
        )
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete recipe?") },
            text = { Text("“${recipe.title}” will be removed permanently.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun IngredientRow(ingredientMatch: IngredientMatch, modifier: Modifier = Modifier) {
    val ingredient = ingredientMatch.ingredient
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        modifier = modifier.fillMaxWidth(),
    ) {
        Image(
            painter = painterResource(
                resolveIconRes(ingredient.foodConceptId, null, ingredient.displayName),
            ),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ingredient.displayName +
                    if (ingredient.optional) " (optional)" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            val amountText = ingredient.amount?.let { amount ->
                formatQuantity(amount) + (ingredient.unit?.let { " $it" }.orEmpty())
            }
            if (amountText != null) {
                Text(
                    text = amountText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val expiringItem = ingredientMatch.matchedItems.firstOrNull()
            ?.takeIf { (ingredientMatch.minDaysUntilExpiry ?: Long.MAX_VALUE) <= 3 }
        if (expiringItem != null) {
            val label = expiryUrgency(expiringItem)?.label
            if (label != null) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(
                            horizontal = MaterialTheme.spacing.small,
                            vertical = MaterialTheme.spacing.extraSmall / 2,
                        ),
                    )
                }
            }
        }
        when (ingredientMatch.status) {
            IngredientStatus.HAVE -> Icon(
                Icons.Default.Check,
                contentDescription = "In stock",
                tint = MaterialTheme.colorScheme.primary,
            )
            IngredientStatus.PARTIAL -> Text(
                text = "only some",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IngredientStatus.MISSING -> Text(
                text = if (ingredient.optional) "not needed" else "missing",
                style = MaterialTheme.typography.labelSmall,
                color = if (ingredient.optional) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

