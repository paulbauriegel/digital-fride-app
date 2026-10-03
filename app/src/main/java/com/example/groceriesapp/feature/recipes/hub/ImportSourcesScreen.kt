package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.recipes.RecipeSourceSite
import com.example.groceriesapp.ui.components.InitialsBadge
import com.example.groceriesapp.ui.components.SettingsChevron
import com.example.groceriesapp.ui.components.SettingsGroup
import com.example.groceriesapp.ui.components.SettingsRow
import com.example.groceriesapp.ui.components.SettingsRowDivider
import com.example.groceriesapp.ui.components.SettingsSectionHeader
import com.example.groceriesapp.ui.theme.spacing

/** Curated cooking sites whose links import without any API key. Picking one leads to the URL step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportSourcesScreen(
    sites: List<RecipeSourceSite>,
    onPick: (RecipeSourceSite) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Import from sources") },
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
            SettingsSectionHeader(
                title = "Supported sites",
                supportingText = "These sites publish structured recipe data, so any recipe link " +
                    "from them imports exactly — amounts, steps and all — without an API key.",
            )
            SettingsGroup {
                sites.forEachIndexed { index, site ->
                    if (index > 0) SettingsRowDivider(inset = 72.dp)
                    SettingsRow(
                        headline = site.name,
                        supportingText = site.host,
                        leadingContent = { InitialsBadge(label = site.name, size = 40.dp) },
                        trailingContent = { SettingsChevron() },
                        onClick = { onPick(site) },
                    )
                }
            }
        }
    }
}
