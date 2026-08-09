package com.example.groceriesapp.feature.receipts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.ReceiptSource
import com.example.groceriesapp.data.insights.InsightsAggregator
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.components.GroceriesSearchBar
import com.example.groceriesapp.ui.components.MerchantBadge
import com.example.groceriesapp.ui.components.rememberGroceriesSearchState
import com.example.groceriesapp.ui.formatCurrency
import com.example.groceriesapp.ui.formatReceiptTimestamp
import com.example.groceriesapp.ui.theme.spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

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
    bioCompanyConnected: Boolean,
    reweConnected: Boolean,
    onScanReceipt: () -> Unit,
    onOpenReceipt: (String) -> Unit,
    onImportBioCompany: () -> Unit,
    onImportRewe: () -> Unit,
    onOpenStoreSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchState = rememberGroceriesSearchState()
    val searchScope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(ReceiptFilter.ALL) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }

    // A rolling 30-day window rather than the calendar month: on the 2nd of a month a
    // month-to-date total says almost nothing about how much is being spent.
    val spendSummary = remember(receipts) {
        InsightsAggregator().rollingSpendSummary(receipts, LocalDate.now())
    }

    // The dropdown filter drives the main list; the query only ever narrows the search view, so
    // the two are computed separately rather than the list filtering itself behind the overlay.
    val filtered = remember(receipts, filter) {
        receipts.filter { receipt -> filter.matches(receipt) }
    }
    val sections = remember(filtered) { groupByRecency(filtered) }

    val searchQuery = searchState.query
    val searchResults = remember(filtered, searchQuery) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            filtered.filter { receipt ->
                receipt.merchant?.contains(searchQuery, ignoreCase = true) == true ||
                    receipt.rawText.contains(searchQuery, ignoreCase = true) ||
                    receipt.items.any { item -> item.name.contains(searchQuery, ignoreCase = true) }
            }
        }
    }

    // Pinned rather than collapsing: the bar carries the search/filter actions, so it
    // stays put and only picks up the scrolled container colour that marks the boundary.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Receipts") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { searchScope.launch { searchState.expand() } }) {
                        Icon(Icons.Default.Search, contentDescription = "Search receipts")
                    }
                    Box {
                        IconButton(onClick = { filterMenuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
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
                    IconButton(onClick = onOpenStoreSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addSheetOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add receipt")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                receipts.isEmpty() -> EmptyStateCard(
                    title = "Keep every receipt in one piece",
                    body = "Record across the receipt, or import purchases from a connected " +
                        "store account. The archive stays on this device.",
                    actionLabel = "Add receipt",
                    onAction = { addSheetOpen = true },
                    modifier = Modifier
                        .weight(1f)
                        .padding(MaterialTheme.spacing.medium),
                )

                filtered.isEmpty() -> EmptyStateCard(
                    title = "Nothing found",
                    body = "No receipts match this filter.",
                    actionLabel = "Show all receipts",
                    onAction = { filter = ReceiptFilter.ALL },
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
                        // Clearance for the FAB so the last row stays reachable.
                        bottom = MaterialTheme.spacing.threeExtraLarge * 2,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    if (spendSummary.totalCents > 0L) {
                        item(key = "spend-summary") {
                            SpendSummaryCard(
                                summary = spendSummary,
                                previousPeriodName = "previous 30 days",
                                modifier = Modifier.padding(top = MaterialTheme.spacing.small),
                            )
                        }
                    }
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
        }
    }

    GroceriesSearchBar(
        state = searchState,
        placeholder = "Search merchant or item",
    ) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            items(searchResults, key = DigitalReceipt::id) { receipt ->
                ReceiptRow(
                    receipt = receipt,
                    onClick = {
                        searchScope.launch { searchState.collapse() }
                        onOpenReceipt(receipt.id)
                    },
                )
            }
        }
    }

    if (addSheetOpen) {
        AddReceiptSheet(
            bioCompanyConnected = bioCompanyConnected,
            reweConnected = reweConnected,
            onScanReceipt = onScanReceipt,
            onImportBioCompany = onImportBioCompany,
            onImportRewe = onImportRewe,
            onOpenStoreSettings = onOpenStoreSettings,
            onDismiss = { addSheetOpen = false },
        )
    }
}

@Composable
private fun ReceiptRow(
    receipt: DigitalReceipt,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
                    formatReceiptTimestamp(receipt.dateEpochMillis, receipt.createdAtEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatCurrency(receipt.totalCents, receipt.currency),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${receipt.items.size} ${if (receipt.items.size == 1) "item" else "items"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
