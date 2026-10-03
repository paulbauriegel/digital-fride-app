package com.example.groceriesapp.feature.recipes.hub

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.recipes.Recipe
import com.example.groceriesapp.data.recipes.RecipeReviewDraft
import com.example.groceriesapp.feature.recipes.RecipeEditorScreen
import com.example.groceriesapp.ui.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "We've extracted the recipe. Review and edit before saving." — the editor prefilled with what
 * the scan or link produced, headed by the source photo and, for scans, the raw OCR text so a
 * mis-read line can be checked against what the camera actually saw.
 */
@Composable
internal fun ScanReviewScreen(
    draft: RecipeReviewDraft,
    onSave: (Recipe) -> Unit,
    onRetake: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A new draft (second scan) must reset the form; keying on identity does that.
    key(draft) {
        RecipeEditorScreen(
            initial = draft.recipe,
            onSave = onSave,
            onBack = onBack,
            modifier = modifier,
            screenTitle = "Review recipe",
            saveLabel = "Save recipe",
            topBarActions = {
                if (onRetake != null) {
                    IconButton(onClick = onRetake) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retake photo")
                    }
                }
            },
            header = {
                Text(
                    text = if (draft.recipe != null) {
                        "We've extracted the recipe from ${draft.sourceLabel.lowercase()}. " +
                            "Review and edit before saving."
                    } else {
                        "The text was read but did not parse as a recipe. Fill in the form " +
                            "using the recognised text below."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val imageFile = draft.imageFile
                if (imageFile != null) {
                    val bitmap by produceState<ImageBitmap?>(null, imageFile) {
                        value = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(imageFile.path)?.asImageBitmap()
                        }
                    }
                    bitmap?.let { loaded ->
                        Image(
                            bitmap = loaded,
                            contentDescription = "Scanned recipe photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(MaterialTheme.shapes.medium),
                        )
                    }
                }
                val rawText = draft.rawText
                if (!rawText.isNullOrBlank()) {
                    var expanded by rememberSaveable { mutableStateOf(draft.recipe == null) }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = MaterialTheme.spacing.medium,
                                vertical = MaterialTheme.spacing.small,
                            ),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                        ) {
                            TextButton(
                                onClick = { expanded = !expanded },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(if (expanded) "Hide recognised text" else "Show recognised text")
                            }
                            if (expanded) {
                                Text(
                                    text = rawText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

