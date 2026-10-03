package com.example.groceriesapp.feature.receipts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.ui.datePickerMillisToReceiptDate
import com.example.groceriesapp.ui.formatDate
import com.example.groceriesapp.ui.localMillisToDatePicker
import com.example.groceriesapp.ui.theme.spacing
import kotlin.math.roundToInt

/**
 * Corrects a receipt's purchase date and total.
 *
 * Both come from the scan and both are regularly wrong: the date line is often cropped or misread,
 * and the total is picked out of a block that also carries subtotals, change and deposit refunds.
 * The date also orders the receipt list and feeds the monthly spend card, so a misread one puts the
 * receipt in the wrong month with the wrong amount.
 *
 * Either field may be cleared: an unset date shows the receipt as undated, an unset total as "—".
 * Nothing here touches the line items — the total is the store's figure, not their sum.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditReceiptSummaryDialog(
    dateEpochMillis: Long?,
    totalCents: Int?,
    currency: String,
    onConfirm: (dateEpochMillis: Long?, totalCents: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var date by rememberSaveable { mutableStateOf(dateEpochMillis) }
    // Typed in whole currency units; cents are a storage detail, not something to ask the user for.
    var total by rememberSaveable {
        mutableStateOf(totalCents?.let { "%.2f".format(it / 100.0) }.orEmpty())
    }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    val parsedTotal = total.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    // Only typed-in nonsense is an error; an emptied field is a deliberate "unknown".
    val totalInvalid = total.isNotBlank() && parsedTotal == null

    fun save() {
        if (totalInvalid) return
        onConfirm(date, if (total.isBlank()) null else parsedTotal?.times(100)?.roundToInt())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null) },
        title = { Text("Edit date & total") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                Text(
                    "Purchase date",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                        Text(formatDate(date))
                    }
                    if (date != null) {
                        TextButton(onClick = { date = null }) { Text("Clear") }
                    }
                }
                OutlinedTextField(
                    value = total,
                    onValueChange = { total = it },
                    label = { Text("Total ($currency)") },
                    isError = totalInvalid,
                    supportingText = {
                        if (totalInvalid) {
                            Text(
                                "Enter an amount, e.g. 24,95.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text("Leave empty if the receipt total is unknown.")
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = !totalInvalid, onClick = { save() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showDatePicker) {
        // The picker speaks UTC midnight; a receipt date is a system-zone instant.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date?.let(::localMillisToDatePicker),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            date = datePickerMillisToReceiptDate(it, date)
                        }
                        showDatePicker = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) { Text("Set date") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
