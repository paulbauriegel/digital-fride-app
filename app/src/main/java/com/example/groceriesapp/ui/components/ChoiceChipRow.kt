package com.example.groceriesapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.groceriesapp.ui.theme.spacing

/**
 * A wrapping row of multi-select filter chips with a leading "All" chip that is selected exactly
 * when nothing else is — the Filters screen pattern. Toggling a selected option removes it;
 * tapping "All" clears the set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChipRow(
    options: List<T>,
    selected: Set<T>,
    label: (T) -> String,
    onSelectionChange: (Set<T>) -> Unit,
    modifier: Modifier = Modifier,
    allLabel: String? = "All",
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        if (allLabel != null) {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = { onSelectionChange(emptySet()) },
                label = { Text(allLabel) },
            )
        }
        options.forEach { option ->
            val isSelected = option in selected
            FilterChip(
                selected = isSelected,
                onClick = {
                    onSelectionChange(if (isSelected) selected - option else selected + option)
                },
                label = { Text(label(option)) },
            )
        }
    }
}

/** Single-select variant: exactly one option is active at a time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SingleChoiceChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}
