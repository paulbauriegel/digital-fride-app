package com.example.groceriesapp.feature.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.ConsumptionReason
import com.example.groceriesapp.data.household.Household
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
