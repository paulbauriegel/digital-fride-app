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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.recipes.GenerationGrouping
import com.example.groceriesapp.data.recipes.GenerationResultTab
import com.example.groceriesapp.data.recipes.RecipeGenerationResult
import com.example.groceriesapp.data.recipes.RecipeMatch
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.theme.spacing
import java.io.File

/** The generated batch in three views, each card openable and bookmarkable like any recipe. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenerateResultsScreen(
    result: RecipeGenerationResult,
    matches: List<RecipeMatch>,
    favoriteIds: Set<String>,
    imagesDir: File,
    imageVersion: Int,
    aiBusy: Boolean,
    aiError: String?,
    onOpenRecipe: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRegenerate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(GenerationResultTab.BEST_MATCH) }
    val groups = remember(matches) { GenerationGrouping.group(matches) }
    val shown = groups[tab].orEmpty()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Recipe Ideas")
                        Text(
                            text = result.request.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                GenerationResultTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        text = { Text(entry.label) },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = MaterialTheme.spacing.medium,
                    end = MaterialTheme.spacing.medium,
                    top = MaterialTheme.spacing.medium,
                    bottom = MaterialTheme.spacing.large,
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                if (aiBusy) {
                    item(key = "busy") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(
                                "Generating a fresh batch…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (aiError != null) {
                    item(key = "error") {
                        Text(
                            text = aiError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        EmptyStateCard(
                            title = when (tab) {
                                GenerationResultTab.BEST_MATCH -> "No ideas in this batch"
                                GenerationResultTab.QUICK_EASY -> "Nothing quick in this batch"
                                GenerationResultTab.HIGH_PROTEIN -> "Nothing high-protein in this batch"
                            },
                            body = "Try Regenerate, or adjust the preferences and cooking time.",
                            icon = Icons.Default.AutoAwesome,
                        )
                    }
                }
                items(shown, key = { it.recipe.id }) { match ->
                    Column {
                        BestMatchCard(
                            match = match,
                            favorite = match.recipe.id in favoriteIds,
                            imagesDir = imagesDir,
                            imageVersion = imageVersion,
                            onOpen = { onOpenRecipe(match.recipe.id) },
                            onToggleFavorite = { onToggleFavorite(match.recipe.id) },
                        )
                        if (GenerationGrouping.isGoodMatch(match)) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(
                                    start = MaterialTheme.spacing.medium,
                                    top = MaterialTheme.spacing.extraSmall,
                                ),
                            ) {
                                Text(
                                    text = "Good match",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(
                                        horizontal = MaterialTheme.spacing.small,
                                        vertical = MaterialTheme.spacing.extraSmall / 2,
                                    ),
                                )
                            }
                        }
                    }
                }
                item(key = "regenerate") {
                    OutlinedButton(
                        onClick = onRegenerate,
                        enabled = !aiBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MaterialTheme.spacing.small),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("Regenerate", modifier = Modifier.padding(start = MaterialTheme.spacing.small))
                    }
                }
            }
        }
    }
}
