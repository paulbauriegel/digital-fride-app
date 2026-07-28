package com.example.groceriesapp.feature.receipts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.formatCurrency
import com.example.groceriesapp.ui.formatDateTime
import com.example.groceriesapp.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptsScreen(
    receipts: List<DigitalReceipt>,
    onScanReceipt: () -> Unit,
    onOpenReceipt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(receipts, query) {
        if (query.isBlank()) receipts else receipts.filter { receipt ->
            receipt.merchant?.contains(query, ignoreCase = true) == true ||
                receipt.rawText.contains(query, ignoreCase = true) ||
                receipt.items.any { it.name.contains(query, ignoreCase = true) }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Receipts") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (receipts.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Search merchant or item") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.medium),
                )
            }

            if (receipts.isEmpty()) {
                EmptyStateCard(
                    title = "Keep every receipt in one piece",
                    body = "Record across the receipt. The archive keeps one continuous panorama, selected keyframes, and recognized text on this device.",
                    actionLabel = "Scan receipt",
                    onAction = onScanReceipt,
                    modifier = Modifier.padding(MaterialTheme.spacing.medium),
                )
            } else if (filtered.isEmpty()) {
                EmptyStateCard(
                    title = "Nothing found",
                    body = "Try another merchant, product, or phrase.",
                    actionLabel = "Clear search",
                    onAction = { query = "" },
                    icon = Icons.Default.Search,
                    modifier = Modifier.padding(MaterialTheme.spacing.medium),
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = MaterialTheme.spacing.medium,
                        top = MaterialTheme.spacing.medium,
                        end = MaterialTheme.spacing.medium,
                        bottom = 112.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    item {
                        Text(
                            "${filtered.size} ${if (filtered.size == 1) "receipt" else "receipts"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(filtered, key = DigitalReceipt::id) { receipt ->
                        ReceiptArchiveRow(
                            receipt = receipt,
                            onClick = { onOpenReceipt(receipt.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptArchiveRow(
    receipt: DigitalReceipt,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    receipt.merchant ?: "Unrecognized receipt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    formatDateTime(receipt.dateEpochMillis ?: receipt.createdAtEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${receipt.items.size} recognized ${
                        if (receipt.items.size == 1) "item" else "items"
                    } • ${receipt.tilePaths.size} image ${
                        if (receipt.tilePaths.size == 1) "tile" else "tiles"
                    }",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatCurrency(receipt.totalCents, receipt.currency),
                    style = MaterialTheme.typography.titleMedium,
                )
                Icon(Icons.Default.ChevronRight, contentDescription = "Open receipt")
            }
        }
    }
}
