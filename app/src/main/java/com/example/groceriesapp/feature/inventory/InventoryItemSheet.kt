package com.example.groceriesapp.feature.inventory

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import com.example.groceriesapp.data.taxonomy.StorageLocation
import com.example.groceriesapp.data.taxonomy.resolveCategory
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.ui.formatExpiry
import com.example.groceriesapp.ui.formatQuantity
import com.example.groceriesapp.ui.theme.spacing
import com.example.groceriesapp.data.household.Household

/**
 * Detail/edit sheet for a single inventory item: category correction, storage location, remaining
 * amount, opened state, and removal. Removal asks whether the item was used up or thrown away —
 * that answer feeds the consumption log — with "just remove" as the no-trace correction path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryItemSheet(
    item: InventoryItem,
    onDismiss: () -> Unit,
    onSetLocation: (StorageLocation) -> Unit,
    onSetFillLevel: (FillLevel?) -> Unit,
    onSetConcept: (String) -> Unit,
    onMarkOpened: () -> Unit,
    onRemove: (ConsumptionReason?) -> Unit,
    /** Households other than the active one; empty when there is nowhere to move the item. */
    otherHouseholds: List<Household> = emptyList(),
    onMoveToHousehold: (String) -> Unit = {},
) {
    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }
    var showConceptPicker by rememberSaveable { mutableStateOf(false) }
    var showHouseholdPicker by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Scrollable because the content is taller than the sheet's resting height and
                // grows further with a large font scale — without this the actions at the bottom
                // are simply unreachable rather than merely below the fold.
                .verticalScroll(rememberScrollState())
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

            Text("Category", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = { showConceptPicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Image(
                    painter = painterResource(
                        resolveIconRes(item.foodConceptId, item.category, item.name),
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = FoodConceptCatalog.byId(item.foodConceptId)?.displayName
                        ?: resolveCategory(item.category, item.name).displayName,
                    modifier = Modifier.padding(start = MaterialTheme.spacing.extraSmall),
                )
            }

            Text("Stored in", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                StorageLocation.entries.forEachIndexed { index, location ->
                    SegmentedButton(
                        modifier = Modifier.minimumInteractiveComponentSize(),
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
                        modifier = Modifier.minimumInteractiveComponentSize(),
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

            // Only worth showing when there is somewhere else to put it.
            if (otherHouseholds.isNotEmpty()) {
                Text("Household", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(
                    onClick = { showHouseholdPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Move to another household",
                        modifier = Modifier.padding(start = MaterialTheme.spacing.extraSmall),
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

    if (showConceptPicker) {
        FoodConceptPickerDialog(
            currentConceptId = item.foodConceptId,
            onPick = { concept ->
                onSetConcept(concept.id)
                showConceptPicker = false
            },
            onDismiss = { showConceptPicker = false },
        )
    }

    if (showRemoveDialog) {
        RemoveItemChoiceDialog(
            itemName = item.name,
            onChoose = onRemove,
            onDismiss = { showRemoveDialog = false },
        )
    }

    if (showHouseholdPicker) {
        MoveToHouseholdDialog(
            itemName = item.name,
            households = otherHouseholds,
            onPick = { household ->
                showHouseholdPicker = false
                onMoveToHousehold(household.id)
            },
            onDismiss = { showHouseholdPicker = false },
        )
    }
}

/**
 * Household picker for moving one item. A move is immediate and unconfirmed because it is not
 * destructive — the item keeps every field and can be moved straight back from the other side.
 */
@Composable
private fun MoveToHouseholdDialog(
    itemName: String,
    households: List<Household>,
    onPick: (Household) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move $itemName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                Text(
                    "The item keeps its amount, expiry and storage location. Its receipt stays " +
                        "visible from both households.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                households.forEach { household ->
                    OutlinedButton(
                        onClick = { onPick(household) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            household.name,
                            modifier = Modifier.padding(
                                start = MaterialTheme.spacing.extraSmall,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * The removal question shared by the detail sheet and the list row's remove icon: eaten or wasted,
 * and the answer feeds the consumption log.
 *
 * There is deliberately no third "just remove, no trace" answer any more. Leaving no trace still
 * blocked the receipt line from ever being imported again, so the quiet option was the one that
 * recorded nothing and cost the most; food leaving the house is overwhelmingly food that got eaten,
 * so that is now the default reading of a plain removal.
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
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                    Text("Used up")
                }
                TextButton(onClick = { onChoose(ConsumptionReason.THROWN_AWAY) }) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                    Text("Thrown away")
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
