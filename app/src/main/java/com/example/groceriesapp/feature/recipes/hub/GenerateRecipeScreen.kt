package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.recipes.GenerationPreference
import com.example.groceriesapp.data.recipes.GenerationTime
import com.example.groceriesapp.ui.components.ChoiceChipRow
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.components.SettingsChevron
import com.example.groceriesapp.ui.components.SettingsGroup
import com.example.groceriesapp.ui.components.SettingsRow
import com.example.groceriesapp.ui.components.SettingsRowDivider
import com.example.groceriesapp.ui.components.SettingsSectionHeader
import com.example.groceriesapp.ui.components.SingleChoiceChipRow
import com.example.groceriesapp.ui.theme.spacing

/**
 * Structured inputs for AI generation: which inventory items to build around, dietary
 * preferences, and how much time there is. Free-text or photo briefs stay available as a
 * secondary row for the "make me exactly this" case.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenerateRecipeScreen(
    inventory: List<InventoryItem>,
    selectedIds: Set<String>,
    preferences: Set<GenerationPreference>,
    time: GenerationTime,
    aiEnabled: Boolean,
    aiBusy: Boolean,
    aiError: String?,
    hasHistory: Boolean,
    onSelectedChange: (Set<String>) -> Unit,
    onPreferencesChange: (Set<GenerationPreference>) -> Unit,
    onTimeChange: (GenerationTime) -> Unit,
    onGenerate: () -> Unit,
    onDescribe: () -> Unit,
    onHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val selectedItems = inventory.filter { it.id in selectedIds }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Generate with AI") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (hasHistory) {
                        IconButton(onClick = onHistory) {
                            Icon(Icons.Default.History, contentDescription = "Previous generations")
                        }
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.spacing.small),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(modifier = Modifier.padding(20.dp)) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                Text(
                    text = "Get recipe ideas tailored to you",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Based on your ingredients, preferences and what you like.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (!aiEnabled) {
                EmptyStateCard(
                    title = "AI is not set up yet",
                    body = "Add an OpenAI API key in Settings to generate recipes.",
                    actionLabel = "Open Settings",
                    onAction = onOpenSettings,
                    icon = Icons.Default.AutoAwesome,
                )
            }

            SettingsSectionHeader(title = "What do you have?")
            SettingsGroup {
                SettingsRow(
                    headline = "Select ingredients",
                    supportingText = when (selectedItems.size) {
                        0 -> "From your inventory — or leave empty to use everything"
                        in 1..3 -> selectedItems.joinToString { it.name }
                        else -> selectedItems.take(3).joinToString { it.name } + " and ${selectedItems.size - 3} more"
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { SettingsChevron() },
                    onClick = { pickerOpen = true },
                )
                SettingsRowDivider(inset = 56.dp)
                SettingsRow(
                    headline = "Describe or photograph a dish",
                    supportingText = "Ask for one specific recipe instead",
                    leadingContent = {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { SettingsChevron() },
                    enabled = aiEnabled && !aiBusy,
                    onClick = onDescribe,
                )
            }

            SettingsSectionHeader(title = "Preferences (optional)")
            ChoiceChipRow(
                options = GenerationPreference.entries,
                selected = preferences,
                label = { it.label },
                onSelectionChange = onPreferencesChange,
                allLabel = null,
            )

            SettingsSectionHeader(title = "Cooking time")
            SingleChoiceChipRow(
                options = GenerationTime.entries,
                selected = time,
                label = { it.label },
                onSelect = onTimeChange,
            )

            if (aiBusy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        "Asking the kitchen AI for ideas…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (aiError != null) {
                Text(
                    text = aiError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onGenerate,
                enabled = aiEnabled && !aiBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MaterialTheme.spacing.small),
            ) {
                Text("Generate ideas")
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = MaterialTheme.spacing.small)
                        .size(18.dp),
                )
            }
        }
    }

    if (pickerOpen) {
        InventoryPickerSheet(
            inventory = inventory,
            selectedIds = selectedIds,
            onSelectionChange = onSelectedChange,
            onDismiss = { pickerOpen = false },
        )
    }
}
