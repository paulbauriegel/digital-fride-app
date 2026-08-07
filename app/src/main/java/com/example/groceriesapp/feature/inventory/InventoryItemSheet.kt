package com.example.groceriesapp.feature.inventory

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.ConsumptionReason
import com.example.groceriesapp.data.FillLevel
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.taxonomy.StorageLocation
import com.example.groceriesapp.data.taxonomy.resolveCategory
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.ui.formatExpiry
import com.example.groceriesapp.ui.formatQuantity
import com.example.groceriesapp.ui.theme.spacing

/**
 * Detail/edit sheet for a single inventory item: storage location, remaining amount, opened
 * state, and removal. Removal asks whether the item was used up or thrown away — that answer
 * feeds the consumption log — with "just remove" as the no-trace correction path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryItemSheet(
    item: InventoryItem,
    onDismiss: () -> Unit,
    onSetLocation: (StorageLocation) -> Unit,
    onSetFillLevel: (FillLevel?) -> Unit,
    onMarkOpened: () -> Unit,
    onRemove: (ConsumptionReason?) -> Unit,
) {
    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MaterialTheme.spacing.medium,
                    end = MaterialTheme.spacing.medium,
                    bottom = MaterialTheme.spacing.extraLarge,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Image(
                        painter = painterResource(
                            resolveIconRes(item.foodConceptId, item.category, item.name),
                        ),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(32.dp),
                    )
                }
                Column {
                    Text(item.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = buildString {
                            append(formatQuantity(item.quantity))
                            item.unit?.takeIf(String::isNotBlank)?.let { append(" $it") }
                            append(" • ")
                            append(resolveCategory(item.category, item.name).displayName)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = buildString {
                            append(formatExpiry(item))
                            if (item.openedAtEpochMillis != null) append(" • opened")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text("Stored in", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                StorageLocation.entries.forEachIndexed { index, location ->
                    SegmentedButton(
                        selected = item.storageLocation == location.name,
                        onClick = { onSetLocation(location) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = StorageLocation.entries.size,
                        ),
                        label = { Text(location.displayName) },
                    )
                }
            }
            if (item.storageLocation != null) {
                Text(
                    "Moving an item re-estimates its expiry from today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Amount left", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                FillLevel.entries.forEachIndexed { index, level ->
                    SegmentedButton(
                        selected = item.fillLevel == level.name,
                        // Tapping the active level clears it back to "not tracked".
                        onClick = {
                            onSetFillLevel(if (item.fillLevel == level.name) null else level)
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = FillLevel.entries.size,
                        ),
                        label = { Text(level.displayName) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                if (item.openedAtEpochMillis == null) {
                    OutlinedButton(
                        onClick = onMarkOpened,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "Mark as opened",
                            modifier = Modifier.padding(start = MaterialTheme.spacing.extraSmall),
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showRemoveDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Remove",
                        modifier = Modifier.padding(start = MaterialTheme.spacing.extraSmall),
                    )
                }
            }
        }
    }

    if (showRemoveDialog) {
        RemoveItemChoiceDialog(
            itemName = item.name,
            onChoose = onRemove,
            onDismiss = { showRemoveDialog = false },
        )
    }
}

/**
 * The removal question shared by the detail sheet and the list row's bin icon: the used-up vs.
 * thrown-away answer feeds the consumption log, "just remove" leaves no trace.
 */
@Composable
internal fun RemoveItemChoiceDialog(
    itemName: String,
    onChoose: (ConsumptionReason?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $itemName?") },
        text = {
            Text(
                "Telling the app what happened helps it understand what your household " +
                    "actually uses and wastes.",
            )
        },
        confirmButton = {
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                TextButton(onClick = { onChoose(ConsumptionReason.USED_UP) }) {
                    Text("Used up")
                }
                TextButton(onClick = { onChoose(ConsumptionReason.THROWN_AWAY) }) {
                    Text("Thrown away")
                }
                TextButton(onClick = { onChoose(null) }) {
                    Text("Just remove")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
