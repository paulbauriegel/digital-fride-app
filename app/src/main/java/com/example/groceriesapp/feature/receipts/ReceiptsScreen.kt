package com.example.groceriesapp.feature.receipts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.ReceiptSource
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.components.MerchantBadge
import com.example.groceriesapp.ui.formatCurrency
import com.example.groceriesapp.ui.formatReceiptTimestamp
import com.example.groceriesapp.ui.theme.spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private enum class ReceiptFilter(val label: String) {
    ALL("All receipts"),
    SCANNED("Scanned"),
    BIO_COMPANY("BIO COMPANY"),
    REWE("REWE"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptsScreen(
    receipts: List<DigitalReceipt>,
    onScanReceipt: () -> Unit,
    onOpenReceipt: (String) -> Unit,
    onImportReceipt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(ReceiptFilter.ALL) }
    var filterMenuOpen by remember { mutableStateOf(false) }

    val filtered = remember(receipts, query, filter) {
        receipts
            .filter { receipt -> filter.matches(receipt) }
            .filter { receipt ->
                query.isBlank() ||
                    receipt.merchant?.contains(query, ignoreCase = true) == true ||
                    receipt.rawText.contains(query, ignoreCase = true) ||
                    receipt.items.any { item -> item.name.contains(query, ignoreCase = true) }
            }
    }
    val sections = remember(filtered) { groupByRecency(filtered) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Receipts") },
                actions = {
                    IconButton(
                        onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        },
                    ) {
                        Icon(
                            if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchOpen) "Close search" else "Search receipts",
                        )
                    }
                    Box {
                        IconButton(onClick = { filterMenuOpen = true }) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = "Filter receipts",
                                tint = if (filter == ReceiptFilter.ALL) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                        DropdownMenu(
                            expanded = filterMenuOpen,
                            onDismissRequest = { filterMenuOpen = false },
                        ) {
                            ReceiptFilter.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        filter = option
                                        filterMenuOpen = false
                                    },
                                    trailingIcon = {
                                        if (option == filter) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedVisibility(visible = searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Search merchant or item") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacing.medium,
                            vertical = MaterialTheme.spacing.small,
                        ),
                )
            }

            when {
                receipts.isEmpty() -> EmptyStateCard(
                    title = "Keep every receipt in one piece",
                    body = "Record across the receipt, or import your BIO COMPANY purchases. " +
                        "The archive stays on this device.",
                    actionLabel = "Scan receipt",
                    onAction = onScanReceipt,
                    modifier = Modifier
                        .weight(1f)
                        .padding(MaterialTheme.spacing.medium),
                )

                filtered.isEmpty() -> EmptyStateCard(
                    title = "Nothing found",
                    body = "Try another merchant, product, or phrase.",
                    actionLabel = if (query.isBlank()) "Show all receipts" else "Clear search",
                    onAction = {
                        query = ""
                        filter = ReceiptFilter.ALL
                    },
                    icon = Icons.Default.Search,
                    modifier = Modifier
                        .weight(1f)
                        .padding(MaterialTheme.spacing.medium),
                )

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = MaterialTheme.spacing.medium,
                        end = MaterialTheme.spacing.medium,
                        bottom = MaterialTheme.spacing.small,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    sections.forEach { (label, group) ->
                        item(key = "header-$label") {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    top = MaterialTheme.spacing.small,
                                    bottom = MaterialTheme.spacing.extraSmall,
                                ),
                            )
                        }
                        items(group, key = DigitalReceipt::id) { receipt ->
                            ReceiptRow(
                                receipt = receipt,
                                onClick = { onOpenReceipt(receipt.id) },
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onImportReceipt,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.small,
                    )
                    .height(52.dp),
            ) {
                Text("Import Receipt", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ReceiptRow(
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
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.medium,
                vertical = MaterialTheme.spacing.small + MaterialTheme.spacing.extraSmall,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            MerchantBadge(receipt.merchant)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    receipt.merchant ?: "Unrecognized receipt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatReceiptTimestamp(receipt.dateEpochMillis ?: receipt.createdAtEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatCurrency(receipt.totalCents, receipt.currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${receipt.items.size} ${if (receipt.items.size == 1) "item" else "items"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun ReceiptFilter.matches(receipt: DigitalReceipt): Boolean = when (this) {
    ReceiptFilter.ALL -> true
    ReceiptFilter.SCANNED -> receipt.source == ReceiptSource.CAPTURED
    ReceiptFilter.BIO_COMPANY -> receipt.source == ReceiptSource.BIO_COMPANY
    ReceiptFilter.REWE -> receipt.source == ReceiptSource.REWE
}

/**
 * Buckets an already newest-first list into `Today` / `This Week` / `This Month` / `Earlier`.
 * Empty buckets are dropped so headers only appear when they carry receipts.
 */
private fun groupByRecency(receipts: List<DigitalReceipt>): List<Pair<String, List<DigitalReceipt>>> {
    if (receipts.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return receipts
        .groupBy { receipt ->
            val date = Instant
                .ofEpochMilli(receipt.dateEpochMillis ?: receipt.createdAtEpochMillis)
                .atZone(zone)
                .toLocalDate()
            when {
                !date.isBefore(today) -> 0
                !date.isBefore(today.minusDays(6)) -> 1
                date.year == today.year && date.month == today.month -> 2
                else -> 3
            }
        }
        .toSortedMap()
        .map { (bucket, group) -> RECENCY_LABELS[bucket] to group }
}

private val RECENCY_LABELS = listOf("Today", "This Week", "This Month", "Earlier")
