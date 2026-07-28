package com.example.groceriesapp.feature.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.components.SectionHeader
import com.example.groceriesapp.ui.formatCurrency
import com.example.groceriesapp.ui.formatDate
import com.example.groceriesapp.ui.theme.spacing
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    inventory: List<InventoryItem>,
    receipts: List<DigitalReceipt>,
    onScanReceipt: () -> Unit,
    onOpenInventory: () -> Unit,
    onOpenReceipts: () -> Unit,
    onOpenReceipt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("My Fridge")
                        Text(
                            "Stored locally on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            item {
                FridgeSummaryCard(inventory = inventory)
            }

            if (inventory.isEmpty() && receipts.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = "Turn paper receipts into your fridge",
                        body = "Record the receipt freely. We track the paper, keep only sharp overlapping views, and build one continuous receipt for review.",
                        actionLabel = "Scan first receipt",
                        onAction = onScanReceipt,
                        icon = Icons.Default.CameraAlt,
                    )
                }
                item {
                    FirstScanSteps()
                }
            } else {
                if (inventory.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Your fridge",
                            actionLabel = "View all",
                            onAction = onOpenInventory,
                        )
                    }
                    item {
                        CategoryRow(inventory)
                    }
                    val expiring = inventory.filter(::expiresWithinThreeDays).take(5)
                    if (expiring.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Expiring soon",
                                actionLabel = "View all",
                                onAction = onOpenInventory,
                            )
                        }
                        item {
                            ExpiringRow(expiring)
                        }
                    }
                }

                item {
                    SectionHeader(
                        title = "Recent receipts",
                        actionLabel = "View all",
                        onAction = onOpenReceipts,
                    )
                }
                if (receipts.isEmpty()) {
                    item {
                        EmptyStateCard(
                            title = "No digital receipts yet",
                            body = "Your next scan will appear here as one continuous, searchable receipt.",
                            actionLabel = "Scan receipt",
                            onAction = onScanReceipt,
                        )
                    }
                } else {
                    items(receipts.take(3), key = DigitalReceipt::id) { receipt ->
                        RecentReceiptRow(receipt, onClick = { onOpenReceipt(receipt.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FridgeSummaryCard(inventory: List<InventoryItem>) {
    val categoryCount = inventory.mapNotNull(InventoryItem::category).distinct().size
    val expiringCount = inventory.count(::expiresWithinThreeDays)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.large),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryMetric(inventory.size.toString(), "Items")
            SummaryMetric(categoryCount.toString(), "Categories")
            SummaryMetric(expiringCount.toString(), "Expiring")
        }
    }
}

@Composable
private fun SummaryMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FirstScanSteps() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Text("How receipt recording works", style = MaterialTheme.typography.titleMedium)
            StepRow("1", "Find the paper", "Wait for the live receipt outline.")
            StepRow("2", "Move freely", "Slow movement is fine; sharp overlapping views are selected automatically.")
            StepRow("3", "Review", "Confirm recognized groceries before adding them.")
        }
    }
}

@Composable
private fun StepRow(number: String, title: String, body: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = number,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CategoryRow(inventory: List<InventoryItem>) {
    val categories = inventory.groupBy { it.category ?: "Other" }
        .entries
        .sortedByDescending { it.value.size }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        items(categories, key = { it.key }) { entry ->
            Card(
                modifier = Modifier
                    .width(136.dp)
                    .heightIn(min = 108.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(MaterialTheme.spacing.medium),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(categoryEmoji(entry.key), style = MaterialTheme.typography.headlineSmall)
                    Column {
                        Text(entry.key, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${entry.value.size} ${if (entry.value.size == 1) "item" else "items"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpiringRow(items: List<InventoryItem>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        items(items, key = InventoryItem::id) { item ->
            Card(
                modifier = Modifier
                    .width(124.dp)
                    .heightIn(min = 104.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(MaterialTheme.spacing.medium),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(categoryEmoji(item.category), style = MaterialTheme.typography.titleLarge)
                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(formatDate(item.expiresAtEpochMillis), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RecentReceiptRow(
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
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(receipt.merchant ?: "Receipt", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${formatDate(receipt.dateEpochMillis)} • ${receipt.items.size} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatCurrency(receipt.totalCents, receipt.currency),
                style = MaterialTheme.typography.titleSmall,
            )
            Icon(Icons.Default.ChevronRight, contentDescription = "Open receipt")
        }
    }
}

private fun expiresWithinThreeDays(item: InventoryItem): Boolean {
    val expiry = item.expiresAtEpochMillis ?: return false
    val now = Instant.now()
    val then = Instant.ofEpochMilli(expiry)
    val days = ChronoUnit.DAYS.between(now, then)
    return days in 0..3
}

private fun categoryEmoji(category: String?): String = when (category) {
    "Fruit" -> "🍎"
    "Vegetables" -> "🥦"
    "Dairy" -> "🥛"
    "Meat & Fish" -> "🐟"
    "Bakery" -> "🥖"
    "Pantry" -> "🥫"
    "Drinks" -> "🧃"
    else -> "🛒"
}
