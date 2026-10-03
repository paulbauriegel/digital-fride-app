package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.recipes.CookEntry
import com.example.groceriesapp.data.recipes.Recipe
import com.example.groceriesapp.data.recipes.RecipeStats
import com.example.groceriesapp.data.recipes.formatRelativeDays
import com.example.groceriesapp.feature.recipes.RecipeImage
import com.example.groceriesapp.ui.components.StarRating
import com.example.groceriesapp.ui.theme.spacing
import java.io.File

/** One card in the "Cooked by you" carousel: photo with a "2 days ago" pill, title, stars, time. */
@Composable
internal fun CookedRecipeCard(
    recipe: Recipe,
    lastCook: CookEntry,
    stats: RecipeStats,
    nowEpochMillis: Long,
    imagesDir: File,
    imageVersion: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(156.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Box {
            RecipeImage(
                recipe = recipe,
                imagesDir = imagesDir,
                imageVersion = imageVersion,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
                iconSize = 28.dp,
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(MaterialTheme.spacing.small),
            ) {
                Text(
                    text = formatRelativeDays(lastCook.cookedAtEpochMillis, nowEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.small,
                        vertical = MaterialTheme.spacing.extraSmall / 2,
                    ),
                )
            }
        }
        Column(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.small + MaterialTheme.spacing.extraSmall,
                vertical = MaterialTheme.spacing.small,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Text(
                text = recipe.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            StarRating(rating = stats.averageRating)
            Text(
                text = "${recipe.prepMinutes} min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
