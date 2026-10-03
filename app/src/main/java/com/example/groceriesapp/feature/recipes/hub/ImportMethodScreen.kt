package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.recipes.RecentSource
import com.example.groceriesapp.data.recipes.formatRelativeDays
import com.example.groceriesapp.ui.components.InitialsBadge
import com.example.groceriesapp.ui.components.SettingsChevron
import com.example.groceriesapp.ui.components.SettingsGroup
import com.example.groceriesapp.ui.components.SettingsRow
import com.example.groceriesapp.ui.components.SettingsRowDivider
import com.example.groceriesapp.ui.components.SettingsSectionHeader
import com.example.groceriesapp.ui.theme.spacing

/** "Import Recipe" — choose how: scan a page, pick a known site, paste a link, or type it in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportMethodScreen(
    recentSources: List<RecentSource>,
    nowEpochMillis: Long,
    onScan: () -> Unit,
    onSources: () -> Unit,
    onPasteLink: () -> Unit,
    onWriteRecipe: () -> Unit,
    onOpenRecentSource: (RecentSource) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Import Recipe") },
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
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MaterialTheme.spacing.medium,
                    end = MaterialTheme.spacing.medium,
                    bottom = MaterialTheme.spacing.large,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            SettingsSectionHeader(title = "Choose method")
            SettingsGroup {
                MethodRow(
                    title = "Scan recipe (Photo OCR)",
                    body = "Take a photo of a recipe from a book, magazine or handwritten note.",
                    icon = Icons.Default.PhotoCamera,
                    onClick = onScan,
                )
                SettingsRowDivider(inset = 56.dp)
                MethodRow(
                    title = "Import from sources",
                    body = "Import recipes from supported cooking websites.",
                    icon = Icons.Default.CloudDownload,
                    onClick = onSources,
                )
                SettingsRowDivider(inset = 56.dp)
                MethodRow(
                    title = "Paste recipe link",
                    body = "Paste any recipe URL to import it.",
                    icon = Icons.Default.Link,
                    onClick = onPasteLink,
                )
                SettingsRowDivider(inset = 56.dp)
                MethodRow(
                    title = "Write it yourself",
                    body = "Type in a recipe by hand.",
                    icon = Icons.Default.Edit,
                    onClick = onWriteRecipe,
                )
            }

            if (recentSources.isNotEmpty()) {
                SettingsSectionHeader(title = "Recent sources")
                SettingsGroup {
                    recentSources.forEachIndexed { index, source ->
                        if (index > 0) SettingsRowDivider(inset = 72.dp)
                        SettingsRow(
                            headline = source.name,
                            supportingText = buildString {
                                append(if (source.count == 1) "1 import" else "${source.count} imports")
                                append(" · ")
                                append(
                                    formatRelativeDays(source.lastUsedAtEpochMillis, nowEpochMillis)
                                        .replaceFirstChar(Char::lowercase),
                                )
                            },
                            leadingContent = { InitialsBadge(label = source.name, size = 40.dp) },
                            trailingContent = { SettingsChevron() },
                            onClick = { onOpenRecentSource(source) },
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Imported recipes are saved to your recipes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun MethodRow(
    title: String,
    body: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    SettingsRow(
        headline = title,
        supportingText = body,
        enabled = enabled,
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailingContent = { SettingsChevron() },
        onClick = onClick,
    )
}
