package com.example.groceriesapp.feature.recipes.hub

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.taxonomy.StorageLocation
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.ui.components.SectionHeader
import com.example.groceriesapp.ui.color
import com.example.groceriesapp.ui.expiryUrgency
import com.example.groceriesapp.ui.shortLabel
import com.example.groceriesapp.ui.formatAmount
import com.example.groceriesapp.ui.theme.spacing

/**
 * Pick inventory items for an AI request, grouped by where they are kept so the fridge shelf
 * can be scanned top to bottom. Items that expire soonest sort first within each group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InventoryPickerSheet(
    inventory: List<InventoryItem>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val grouped = remember(inventory) {
        inventory
            .filterNot { it.hidden }
            .groupBy { StorageLocation.fromStorageValue(it.storageLocation) ?: StorageLocation.PANTRY }
            .mapValues { (_, items) ->
                items.sortedWith(compareBy<InventoryItem, Long?>(nullsLast()) { it.expiresAtEpochMillis })
            }
    }
    val order = listOf(StorageLocation.FRIDGE, StorageLocation.FREEZER, StorageLocation.PANTRY)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Select ingredients",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onSelectionChange(emptySet()) },
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Text("Clear")
                }
            }
            Text(
                text = "The recipes will be built around what you pick; everything else in your " +
                    "inventory is still available as a supporting ingredient.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                order.forEach { location ->
                    val items = grouped[location].orEmpty()
                    if (items.isEmpty()) return@forEach
                    item(key = "header-$location") {
                        SectionHeader(
                            title = location.displayName,
                            modifier = Modifier.padding(top = MaterialTheme.spacing.small),
                        )
                    }
                    items(items, key = { it.id }) { item ->
                        val checked = item.id in selectedIds
                        val urgency = expiryUrgency(item)
                        val soon = urgency != null && urgency.daysUntil <= 3
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { now ->
                                    onSelectionChange(if (now) selectedIds + item.id else selectedIds - item.id)
                                },
                            )
                            Image(
                                painter = painterResource(resolveIconRes(item.foodConceptId, item.category, item.name)),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    text = buildString {
                                        append(formatAmount(item.quantity, item.unit))
                                        if (urgency != null && soon) {
                                            append(" · ")
                                            append(urgency.shortLabel())
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (urgency != null && soon) {
                                        urgency.color()
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = MaterialTheme.spacing.small,
                        bottom = MaterialTheme.spacing.extraLarge,
                    ),
            ) {
                Text(
                    when (selectedIds.size) {
                        0 -> "Done"
                        1 -> "Use 1 ingredient"
                        else -> "Use ${selectedIds.size} ingredients"
                    },
                )
            }
        }
    }
}
