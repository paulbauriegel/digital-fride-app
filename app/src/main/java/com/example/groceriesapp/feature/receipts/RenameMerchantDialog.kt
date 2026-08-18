package com.example.groceriesapp.feature.receipts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.groceriesapp.ui.theme.spacing

/**
 * Corrects the store name on a receipt. OCR regularly mangles the store line
 * ("EDEKA 14129 Berlin" scans as "Edeka 14129 Ver11n"), so the name always needs a manual fix-up
 * path. Store names already used on other receipts are offered as chips, so corrections converge
 * on one spelling instead of creating another near-duplicate.
 */
@Composable
fun RenameMerchantDialog(
    currentMerchant: String?,
    suggestions: List<String>,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentMerchant.orEmpty()) }
    val offered = remember(suggestions, name) {
        val typed = name.trim()
        suggestions.filterNot { it.equals(typed, ignoreCase = true) }.take(8)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Storefront, contentDescription = null) },
        title = { Text("Rename store") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Store") },
                    singleLine = true,
                    // Clearing the field is a valid choice: the receipt goes back to unrecognized.
                    supportingText = { Text("Leave empty to show this receipt as unrecognized.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (offered.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        offered.forEach { suggestion ->
                            SuggestionChip(
                                onClick = { name = suggestion },
                                label = { Text(suggestion) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim().takeIf(String::isNotEmpty)) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
