package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.groceriesapp.data.recipes.OrganizeBy
import com.example.groceriesapp.data.recipes.RecipeSort
import com.example.groceriesapp.ui.components.SettingsGroup
import com.example.groceriesapp.ui.components.SettingsRow
import com.example.groceriesapp.ui.components.SettingsRowDivider
import com.example.groceriesapp.ui.components.SettingsSectionHeader
import com.example.groceriesapp.ui.theme.spacing

/** Sort order and grouping for the library. Picking a row applies it immediately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeSortScreen(
    sort: RecipeSort,
    organizeBy: OrganizeBy,
    onSelectSort: (RecipeSort) -> Unit,
    onSelectOrganizeBy: (OrganizeBy) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Sort by") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
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
            SettingsSectionHeader(title = "Sort by")
            SettingsGroup {
                RecipeSort.entries.forEachIndexed { index, option ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        headline = option.label,
                        supportingText = option.description,
                        trailingContent = {
                            RadioButton(selected = option == sort, onClick = { onSelectSort(option) })
                        },
                        onClick = { onSelectSort(option) },
                    )
                }
            }

            SettingsSectionHeader(
                title = "Organize by",
                supportingText = "Group the list under headings instead of one flat list.",
            )
            SettingsGroup {
                OrganizeBy.entries.forEachIndexed { index, option ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        headline = option.label,
                        trailingContent = {
                            RadioButton(
                                selected = option == organizeBy,
                                onClick = { onSelectOrganizeBy(option) },
                            )
                        },
                        onClick = { onSelectOrganizeBy(option) },
                    )
                }
            }
        }
    }
}
