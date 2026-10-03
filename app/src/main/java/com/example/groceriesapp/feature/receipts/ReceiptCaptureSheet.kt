package com.example.groceriesapp.feature.receipts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoLibrary
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
 * The three ways to get a paper receipt into the app, in the order they are worth reaching for.
 *
 * A single photo covers the everyday short receipt and needs neither ARCore nor a steady sweep; the
 * panorama recording is kept for the long ones it was built for. Shared by the Receipts Add sheet
 * and Home's quick action so the choice reads the same wherever it is offered.
 */
internal fun receiptCaptureOptions(
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onScanLongReceipt: () -> Unit,
): List<AddReceiptOption> = listOf(
    AddReceiptOption(
        key = "photo",
        title = "Take photo",
        subtitle = "Snap a short receipt in one shot",
        icon = captureIcon(Icons.Default.CameraAlt),
        onClick = onTakePhoto,
    ),
    AddReceiptOption(
        key = "gallery",
        title = "Choose from gallery",
        subtitle = "Import a receipt photo you already have",
        icon = captureIcon(Icons.Default.PhotoLibrary),
        onClick = onPickPhoto,
    ),
    AddReceiptOption(
        key = "scan",
        title = "Scan long receipt",
        subtitle = "Record along the paper to build one image",
        icon = captureIcon(Icons.Default.DocumentScanner),
        onClick = onScanLongReceipt,
    ),
)

/** Home's chooser: the capture options on their own, without the connected-store imports. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptCaptureSheet(
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onScanLongReceipt: () -> Unit,
    onDismiss: () -> Unit,
) {
    fun dismissThen(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = MaterialTheme.spacing.large)) {
            Text(
                "Scan receipt",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.small,
                ),
            )
            receiptCaptureOptions(
                onTakePhoto = dismissThen(onTakePhoto),
                onPickPhoto = dismissThen(onPickPhoto),
                onScanLongReceipt = dismissThen(onScanLongReceipt),
            ).forEach { option -> AddReceiptOptionRow(option) }
        }
    }
}

internal fun captureIcon(icon: ImageVector): @Composable () -> Unit = {
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
}

@Composable
internal fun AddReceiptOptionRow(option: AddReceiptOption) {
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
