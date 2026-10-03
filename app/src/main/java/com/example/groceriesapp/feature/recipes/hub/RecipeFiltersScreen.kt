package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.recipes.CookTimeBucket
import com.example.groceriesapp.data.recipes.DietaryTag
import com.example.groceriesapp.data.recipes.RecipeCategory
import com.example.groceriesapp.data.recipes.RecipeLibraryQuery
import com.example.groceriesapp.ui.components.ChoiceChipRow
import com.example.groceriesapp.ui.components.SettingsGroup
import com.example.groceriesapp.ui.components.SettingsRow
import com.example.groceriesapp.ui.components.SettingsRowDivider
import com.example.groceriesapp.ui.components.SettingsSectionHeader
import com.example.groceriesapp.ui.theme.spacing

/**
 * Full-screen filter editor for the library. Edits a draft; nothing applies until
 * "Show N recipes", so Back is a cancel. [resultCount] is recomputed by the caller as the draft
 * changes so the button always names what it will show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeFiltersScreen(
    draft: RecipeLibraryQuery,
    resultCount: Int,
    inventory: List<InventoryItem>,
    onDraftChange: (RecipeLibraryQuery) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Filters") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close filters")
                    }
                },
                actions = {
                    TextButton(onClick = onReset, enabled = draft.activeFilterCount > 0) {
                        Text("Reset")
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Button(
                    onClick = onApply,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.spacing.medium),
                ) {
                    Text(if (resultCount == 1) "Show 1 recipe" else "Show $resultCount recipes")
                }
            }
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
            SettingsSectionHeader(title = "Categories")
            ChoiceChipRow(
                options = RecipeCategory.entries,
                selected = draft.categories,
                label = { it.label },
                onSelectionChange = { onDraftChange(draft.copy(categories = it)) },
            )

            SettingsSectionHeader(title = "Cook time")
            ChoiceChipRow(
                options = CookTimeBucket.entries,
                selected = draft.cookTimes,
                label = { it.label },
                onSelectionChange = { onDraftChange(draft.copy(cookTimes = it)) },
            )

            SettingsSectionHeader(title = "Dietary preferences")
            ChoiceChipRow(
                options = DietaryTag.entries,
                selected = draft.dietary,
                label = { it.label },
                onSelectionChange = { onDraftChange(draft.copy(dietary = it)) },
            )

            SettingsSectionHeader(title = "Ingredients")
            IngredientChipEditor(
                label = "Include ingredients",
                values = draft.includeIngredients,
                inventory = inventory,
                onChange = { onDraftChange(draft.copy(includeIngredients = it)) },
            )
            IngredientChipEditor(
                label = "Exclude ingredients",
                values = draft.excludeIngredients,
                inventory = inventory,
                onChange = { onDraftChange(draft.copy(excludeIngredients = it)) },
            )

            SettingsSectionHeader(title = "Other")
            SettingsGroup {
                SettingsRow(
                    headline = "Use expiring ingredients first",
                    supportingText = "Rank recipes that use food about to expire higher",
                    trailingContent = {
                        Switch(
                            checked = draft.useExpiringFirst,
                            onCheckedChange = { onDraftChange(draft.copy(useExpiringFirst = it)) },
                        )
                    },
                    onClick = { onDraftChange(draft.copy(useExpiringFirst = !draft.useExpiringFirst)) },
                )
                SettingsRowDivider()
                SettingsRow(
                    headline = "Only recipes I can make",
                    supportingText = "Based on my inventory",
                    trailingContent = {
                        Switch(
                            checked = draft.onlyWhatICanMake,
                            onCheckedChange = { onDraftChange(draft.copy(onlyWhatICanMake = it)) },
                        )
                    },
                    onClick = { onDraftChange(draft.copy(onlyWhatICanMake = !draft.onlyWhatICanMake)) },
                )
                SettingsRowDivider()
                SettingsRow(
                    headline = "Cooked by me",
                    trailingContent = {
                        Switch(
                            checked = draft.cookedByMe,
                            onCheckedChange = { onDraftChange(draft.copy(cookedByMe = it)) },
                        )
                    },
                    onClick = { onDraftChange(draft.copy(cookedByMe = !draft.cookedByMe)) },
                )
                SettingsRowDivider()
                SettingsRow(
                    headline = "Favorites only",
                    trailingContent = {
                        Switch(
                            checked = draft.favoritesOnly,
                            onCheckedChange = { onDraftChange(draft.copy(favoritesOnly = it)) },
                        )
                    },
                    onClick = { onDraftChange(draft.copy(favoritesOnly = !draft.favoritesOnly)) },
                )
            }
        }
    }
}

/**
 * Chips for the chosen ingredient keys plus a field to add more. Typing offers matching
 * inventory names as suggestions, since what's in the fridge is what people filter by; a free
 * word works too and matches ingredient names by substring.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IngredientChipEditor(
    label: String,
    values: Set<String>,
    inventory: List<InventoryItem>,
    onChange: (Set<String>) -> Unit,
) {
    var text by rememberSaveable(label) { mutableStateOf("") }
    val suggestions = remember(text, inventory) {
        val needle = text.trim()
        if (needle.length < 2) {
            emptyList()
        } else {
            inventory.map { it.name }
                .filter { it.contains(needle, ignoreCase = true) }
                .distinctBy { it.lowercase() }
                .filter { it.lowercase() !in values }
                .take(5)
        }
    }
    fun add(value: String) {
        val key = value.trim().lowercase()
        if (key.isNotEmpty()) onChange(values + key)
        text = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (values.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                values.sorted().forEach { value ->
                    InputChip(
                        selected = true,
                        onClick = { onChange(values - value) },
                        label = { Text(value) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove $value",
                                modifier = Modifier.size(InputChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Add ingredient") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add(text) }),
            trailingIcon = {
                IconButton(onClick = { add(text) }, enabled = text.isNotBlank()) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                suggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = { add(suggestion) },
                        label = { Text(suggestion) },
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}
