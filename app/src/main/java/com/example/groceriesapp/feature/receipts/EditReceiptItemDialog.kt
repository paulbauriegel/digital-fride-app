package com.example.groceriesapp.feature.receipts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.ui.formatQuantity
import com.example.groceriesapp.ui.theme.spacing
import kotlin.math.roundToInt

/**
 * Corrects one receipt line, or adds one the scan missed entirely ([item] null).
 *
 * OCR gets line items wrong in both directions: it mangles names ("G&g b.0.lau.b 0,39 e x 2"),
 * folds the quantity into the name, and picks up payment and deposit lines ("Visa") that were
 * never groceries — so every field needs a manual fix-up path, and every line needs to be
 * removable. Edits keep the line's id, so an import record already attached to it stays attached.
 */
@Composable
internal fun EditReceiptItemDialog(
    item: ReceiptItem?,
    onSave: (ReceiptItem) -> Unit,
    onDismiss: () -> Unit,
    /** Null while adding — there is no line on the receipt yet to remove. */
    onDelete: (() -> Unit)? = null,
    /** Whether this line has already been imported; its inventory row is not touched from here. */
    alreadyImported: Boolean = false,
) {
    val key = item?.id
    var name by rememberSaveable(key) { mutableStateOf(item?.name.orEmpty()) }
    var quantity by rememberSaveable(key) {
        mutableStateOf(item?.quantity?.let(::formatQuantity) ?: "1")
    }
    var unit by rememberSaveable(key) { mutableStateOf(item?.unit.orEmpty()) }
    // Shown and typed in whole currency units — cents are a storage detail, not something to ask
    // the user for.
    var price by rememberSaveable(key) {
        mutableStateOf(item?.priceCents?.let { "%.2f".format(it / 100.0) }.orEmpty())
    }
    val focusManager = LocalFocusManager.current

    val parsedQuantity = quantity.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val parsedPrice = price.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    // Only flag typed-in nonsense; an emptied field is on its way somewhere, not yet wrong. An
    // empty price stays empty — plenty of lines scan without one, and 0,00 would be a claim.
    val quantityInvalid = quantity.isNotBlank() && parsedQuantity == null
    val priceInvalid = price.isNotBlank() && parsedPrice == null
    val canSave = name.isNotBlank() && parsedQuantity != null && !priceInvalid

    fun save() {
        val amount = parsedQuantity ?: return
        if (name.isBlank() || priceInvalid) return
        onSave(
            // Category and concept are left as they were; the repository re-derives both from the
            // new name on the way in, so a corrected name also corrects the icon.
            (item ?: ReceiptItem(name = name.trim())).copy(
                name = name.trim(),
                quantity = amount,
                unit = unit.trim().ifBlank { null },
                priceCents = if (price.isBlank()) null else parsedPrice?.times(100)?.roundToInt(),
                // A hand-entered line is exactly what the user meant it to be.
                needsReview = false,
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (item == null) Icons.Default.AddShoppingCart else Icons.Default.Edit,
                contentDescription = null,
            )
        },
        title = { Text(if (item == null) "Add item" else "Edit item") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                if (alreadyImported) {
                    Text(
                        "This line is already imported. Changes here fix the receipt only — " +
                            "the inventory item it created stays as it is, and removing the " +
                            "line does not remove it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Quantity") },
                        isError = quantityInvalid,
                        supportingText = if (quantityInvalid) {
                            {
                                Text(
                                    "Enter a number above 0.",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Unit") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price") },
                    isError = priceInvalid,
                    supportingText = {
                        if (priceInvalid) {
                            Text("Enter an amount, e.g. 1,69.", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Leave empty if the line carries no price.")
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
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                        Text("Remove from receipt")
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { save() }) {
                Text(if (item == null) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
