package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.groceriesapp.data.recipes.RecipeGenerationResult
import com.example.groceriesapp.data.recipes.formatRelativeDays
import com.example.groceriesapp.ui.components.SettingsChevron
import com.example.groceriesapp.ui.components.SettingsRow
import com.example.groceriesapp.ui.theme.spacing

/** Past generation batches, newest first; tapping one reopens its results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenerationHistorySheet(
    history: List<RecipeGenerationResult>,
    availableRecipeIds: Set<String>,
    nowEpochMillis: Long,
    onOpen: (RecipeGenerationResult) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MaterialTheme.spacing.medium,
                    end = MaterialTheme.spacing.medium,
                    bottom = MaterialTheme.spacing.extraLarge,
                ),
        ) {
            Text(
                text = "Previous generations",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = MaterialTheme.spacing.small),
            )
            LazyColumn {
                items(history, key = { it.id }) { result ->
                    val remaining = result.recipeIds.count { it in availableRecipeIds }
                    SettingsRow(
                        headline = result.request.summary(),
                        supportingText = buildString {
                            append(formatRelativeDays(result.createdAtEpochMillis, nowEpochMillis))
                            append(" · ")
                            append(if (remaining == 1) "1 recipe" else "$remaining recipes")
                            if (remaining < result.recipeIds.size) append(" still saved")
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = { SettingsChevron() },
                        enabled = remaining > 0,
                        onClick = { onOpen(result) },
                    )
                }
            }
        }
    }
}
