package com.example.groceriesapp.feature.receipts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.groceriesapp.ui.components.MerchantBadge
import com.example.groceriesapp.ui.theme.spacing

/** One row of the Add sheet. Data-driven so adding a store later is one more list element. */
data class AddReceiptOption(
    val key: String,
    val title: String,
    val subtitle: String?,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit,
)

/**
 * The Receipts tab's Add entry point: capture a paper receipt or pull digital ones from a connected
 * store account. Unconnected stores stay listed but route to Settings, so the path to connecting
 * them is discoverable right where the user looks for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReceiptSheet(
    bioCompanyConnected: Boolean,
    reweConnected: Boolean,
    edekaConnected: Boolean,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onScanLongReceipt: () -> Unit,
    onImportBioCompany: () -> Unit,
    onImportRewe: () -> Unit,
    onImportEdeka: () -> Unit,
    onOpenStoreSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    fun dismissThen(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    val options = receiptCaptureOptions(
        onTakePhoto = dismissThen(onTakePhoto),
        onPickPhoto = dismissThen(onPickPhoto),
        onScanLongReceipt = dismissThen(onScanLongReceipt),
    ) + listOf(
        AddReceiptOption(
            key = "rewe",
            title = "Import from REWE",
            subtitle = if (reweConnected) "Fetch your eBons" else "Connect your account in Settings",
            icon = { MerchantBadge("REWE") },
            onClick = dismissThen(if (reweConnected) onImportRewe else onOpenStoreSettings),
        ),
        AddReceiptOption(
            key = "edeka",
            title = "Import from EDEKA",
            subtitle = if (edekaConnected) {
                "Fetch your digital receipts"
            } else {
                "Connect your account in Settings"
            },
            icon = { MerchantBadge("EDEKA") },
            onClick = dismissThen(if (edekaConnected) onImportEdeka else onOpenStoreSettings),
        ),
        AddReceiptOption(
            key = "biocompany",
            title = "Import from BIO COMPANY",
            subtitle = if (bioCompanyConnected) {
                "Fetch your digital receipts"
            } else {
                "Connect your account in Settings"
            },
            icon = { MerchantBadge("BIO COMPANY") },
            onClick = dismissThen(if (bioCompanyConnected) onImportBioCompany else onOpenStoreSettings),
        ),
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = MaterialTheme.spacing.large),
        ) {
            Text(
                "Add receipt",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.small,
                ),
            )
            options.forEach { option -> AddReceiptOptionRow(option) }
        }
    }
}
