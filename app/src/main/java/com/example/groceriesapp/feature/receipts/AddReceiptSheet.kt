package com.example.groceriesapp.feature.receipts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * The Receipts tab's Add entry point: scan a paper receipt or pull digital ones from a connected
 * store account. Unconnected stores stay listed but route to Settings, so the path to connecting
 * them is discoverable right where the user looks for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReceiptSheet(
    bioCompanyConnected: Boolean,
    reweConnected: Boolean,
    onScanReceipt: () -> Unit,
    onImportBioCompany: () -> Unit,
    onImportRewe: () -> Unit,
    onOpenStoreSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    fun dismissThen(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    val options = listOf(
        AddReceiptOption(
            key = "scan",
            title = "Scan receipt",
            subtitle = "Capture a paper receipt with the camera",
            icon = {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.DocumentScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            },
            onClick = dismissThen(onScanReceipt),
        ),
        AddReceiptOption(
            key = "rewe",
            title = "Import from REWE",
            subtitle = if (reweConnected) "Fetch your eBons" else "Connect your account in Settings",
            icon = { MerchantBadge("REWE") },
            onClick = dismissThen(if (reweConnected) onImportRewe else onOpenStoreSettings),
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
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = option.onClick)
                        .padding(
                            horizontal = MaterialTheme.spacing.medium,
                            vertical = MaterialTheme.spacing.small + MaterialTheme.spacing.extraSmall,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
                ) {
                    option.icon()
                    Column {
                        Text(option.title, style = MaterialTheme.typography.bodyLarge)
                        option.subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
