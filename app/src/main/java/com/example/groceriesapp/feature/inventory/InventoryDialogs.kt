package com.example.groceriesapp.feature.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.ConsumptionReason
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.household.Household
import com.example.groceriesapp.data.network.OpenFoodFactsProduct
import com.example.groceriesapp.ui.formatAmount
import com.example.groceriesapp.ui.theme.spacing

/**
 * Household picker for moving one item. A move is immediate and unconfirmed because it is not
 * destructive — the item keeps every field and can be moved straight back from the other side.
 */
@Composable
internal fun MoveToHouseholdDialog(
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
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                Text(
                    "Telling the app what happened helps it understand what your household " +
                        "actually uses and wastes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = { onChoose(ConsumptionReason.USED_UP) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                    Text("Used up")
                }
                OutlinedButton(
                    onClick = { onChoose(ConsumptionReason.THROWN_AWAY) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * What Open Food Facts knows about a barcode scanned from an item that already exists, and which
 * parts of it the user wants to keep.
 *
 * Opt-in per field rather than one "update" button: the item on screen already has a name the user
 * recognises and, often, an amount they have been drawing down for days, while an Open Food Facts
 * name can be a brand string and its pack size is the size of a *full* pack. Both boxes start
 * ticked where the answer is likely useful, and neither is applied silently.
 *
 * The barcode itself is always linked on confirm — that is the one fact the scan established
 * beyond doubt, and it costs the item nothing.
 */
@Composable
internal fun ScannedProductDialog(
    item: InventoryItem,
    barcode: String,
    product: OpenFoodFactsProduct?,
    notice: String?,
    onApply: (useName: Boolean, usePackSize: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Offered only when it would actually change something: the same name back, or a pack size the
    // item already carries, is a tick box that does nothing.
    val offeredName = product?.name?.trim()?.takeIf { it.isNotEmpty() && it != item.name }
    val offeredPackSize = product?.quantity?.takeIf {
        it != item.packageSize || !product.unit.equals(item.unit, ignoreCase = true)
    }
    var useName by rememberSaveable(barcode) { mutableStateOf(true) }
    var usePackSize by rememberSaveable(barcode) { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
        title = { Text(if (product != null) "Open Food Facts found this" else "Barcode scanned") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                Text(
                    "Barcode $barcode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                notice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                offeredName?.let { name ->
                    ProductFieldCheckbox(
                        checked = useName,
                        onCheckedChange = { useName = it },
                        title = "Rename to \u201C$name\u201D",
                        subtitle = "Now called \u201C${item.name}\u201D",
                    )
                }
                offeredPackSize?.let { size ->
                    ProductFieldCheckbox(
                        checked = usePackSize,
                        onCheckedChange = { usePackSize = it },
                        title = "A full pack holds ${formatAmount(size, product.unit)}",
                        subtitle = item.packageSize
                            ?.let { "Currently ${formatAmount(it, item.unit)}" }
                            ?: "The item isn\u2019t tracking how much is left yet",
                    )
                }
                Text(
                    "The barcode is linked to this item either way, so scanning this pack again " +
                        "finds it instead of adding a second row.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(offeredName != null && useName, offeredPackSize != null && usePackSize)
                },
            ) {
                Text(if (offeredName != null || offeredPackSize != null) "Apply" else "Link barcode")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ProductFieldCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.padding(start = MaterialTheme.spacing.extraSmall)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
