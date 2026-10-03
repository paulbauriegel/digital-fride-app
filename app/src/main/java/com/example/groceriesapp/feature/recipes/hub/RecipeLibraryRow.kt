package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.recipes.LibraryRow
import com.example.groceriesapp.data.recipes.RecipeSource
import com.example.groceriesapp.data.recipes.formatRelativeDays
import com.example.groceriesapp.feature.recipes.RecipeImage
import com.example.groceriesapp.ui.components.StarRating
import com.example.groceriesapp.ui.theme.spacing
import java.io.File
import kotlin.math.roundToInt

/**
 * A library list row: thumbnail, title, stars, then either when it was last cooked or how much of
 * it the inventory covers — whichever the row actually has something to say about.
 */
@Composable
internal fun RecipeLibraryRow(
    row: LibraryRow,
    nowEpochMillis: Long,
    imagesDir: File,
    imageVersion: Int,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipe = row.recipe
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
                recipe = recipe,
                imagesDir = imagesDir,
                imageVersion = imageVersion,
                modifier = Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.medium),
                iconSize = 24.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    Text(
                        text = recipe.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (recipe.source == RecipeSource.AI) AiTag()
                }
                StarRating(rating = row.stats.averageRating)
                Text(
                    text = buildString {
                        val last = row.stats.lastCookedAtEpochMillis
                        if (last != null) {
                            append("Cooked ")
                            append(formatRelativeDays(last, nowEpochMillis).replaceFirstChar(Char::lowercase))
                            append(" · ${recipe.prepMinutes} min")
                        } else {
                            append("${recipe.prepMinutes} min · You have ")
                            append("${(row.match.coverage * 100).roundToInt()}%")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SaveButton(saved = row.favorite, onToggle = onToggleFavorite)
        }
    }
}
