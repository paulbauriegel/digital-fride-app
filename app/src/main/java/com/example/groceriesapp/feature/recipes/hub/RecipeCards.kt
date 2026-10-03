package com.example.groceriesapp.feature.recipes.hub

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
import com.example.groceriesapp.ui.components.StarRating
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

import com.example.groceriesapp.feature.recipes.RecipeImage
import com.example.groceriesapp.feature.recipes.decodeOrientedBitmap

/** The green "You have 100%" pill from the mockup; softer container color below full coverage. */
@Composable
internal fun CoveragePill(coverage: Double, modifier: Modifier = Modifier) {
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
internal fun ExpiringPill(count: Int, modifier: Modifier = Modifier) {
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
internal fun SaveButton(
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
internal fun AiTag(modifier: Modifier = Modifier) {
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
internal fun BestMatchCard(
    match: RecipeMatch,
    favorite: Boolean,
    imagesDir: File,
    imageVersion: Int,
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
            imageVersion = imageVersion,
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
internal fun MoreIdeaRow(
    match: RecipeMatch,
    favorite: Boolean,
    imagesDir: File,
    imageVersion: Int,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    rating: Double? = null,
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
                imageVersion = imageVersion,
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
                StarRating(rating = rating)
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
