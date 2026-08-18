package com.example.groceriesapp.feature.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.ui.theme.spacing

/**
 * How to add one item: type it, or read the barcode off the pack.
 *
 * A sheet rather than a menu because both entry points — the Inventory toolbar and the Home
 * quick-action tile — need it, and a chooser dialog in front of the add dialog would stack two
 * scrims. Mirrors [AddReceiptSheet][com.example.groceriesapp.feature.receipts.AddReceiptSheet],
 * which already answers the same question for receipts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddItemMethodSheet(
    onAddManually: () -> Unit,
    onScanBarcode: () -> Unit,
    onDismiss: () -> Unit,
) {
    fun dismissThen(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = MaterialTheme.spacing.large)) {
            Text(
                "Add item",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.small,
                ),
            )
            AddItemMethodRow(
                icon = Icons.Default.Edit,
                title = "Enter manually",
                subtitle = "Type the name and amount yourself",
                onClick = dismissThen(onAddManually),
            )
            AddItemMethodRow(
                icon = Icons.Default.QrCodeScanner,
                title = "Scan barcode",
                subtitle = "Look the product up on Open Food Facts",
                onClick = dismissThen(onScanBarcode),
            )
        }
    }
}

@Composable
private fun AddItemMethodRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = MaterialTheme.spacing.medium,
                vertical = MaterialTheme.spacing.small + MaterialTheme.spacing.extraSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
