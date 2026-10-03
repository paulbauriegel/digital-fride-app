package com.example.groceriesapp.feature.receipts

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.data.ReceiptStatus
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.ui.components.MerchantBadge
import com.example.groceriesapp.ui.formatCurrency
import com.example.groceriesapp.ui.formatDate
import com.example.groceriesapp.ui.formatAmount
import com.example.groceriesapp.ui.formatQuantity
import com.example.groceriesapp.ui.theme.spacing

/**
 * Mockup view 5 — one receipt's summary: merchant, total, imported/not-imported split, and the
 * item list. "View receipt" opens the image viewer/editor, "Manage items" the import flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptDetailScreen(
    receipt: DigitalReceipt,
    onBack: () -> Unit,
    onViewReceipt: () -> Unit,
    onManageItems: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRenameMerchant: (String?) -> Unit,
    onEditSummary: (dateEpochMillis: Long?, totalCents: Int?) -> Unit,
    onUpdateItem: (ReceiptItem) -> Unit,
    onAddItem: (ReceiptItem) -> Unit,
    onDeleteItem: (String) -> Unit,
    merchantSuggestions: List<String>,
    modifier: Modifier = Modifier,
) {
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showSummaryDialog by rememberSaveable { mutableStateOf(false) }
    // Held as an id, not the item: the receipt is re-read from the repository on every change, so
    // an item captured here would go stale the moment it is edited.
    var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddItemDialog by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val hasImage = receipt.tilePaths.isNotEmpty() || receipt.originalFramePaths.isNotEmpty()
    val importedCount = receipt.items.count { it.id in receipt.importedItemIds }
    val notImportedCount = receipt.items.size - importedCount

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(receipt.merchant ?: "Receipt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename store") },
                                onClick = {
                                    menuOpen = false
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Edit date & total") },
                                onClick = {
                                    menuOpen = false
                                    showSummaryDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Add item") },
                                onClick = {
                                    menuOpen = false
                                    showAddItemDialog = true
                                },
                            )
                            if (hasImage) {
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    onClick = {
                                        menuOpen = false
                                        onShare()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    menuOpen = false
                                    showDeleteDialog = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MaterialTheme.spacing.medium),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                // API-imported receipts carry no image; "Manage items" then spans the full width.
                if (hasImage) {
                    OutlinedButton(
                        onClick = onViewReceipt,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("View receipt")
                    }
                }
                Button(
                    onClick = onManageItems,
                    enabled = receipt.items.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Manage items")
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.medium,
            ),
        ) {
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                ) {
                    MerchantBadge(receipt.merchant, size = 56.dp)
                    // A misread store line is common enough that the name itself is the edit
                    // affordance; the overflow menu carries the same action.
                    Row(
                        modifier = Modifier.clickable { showRenameDialog = true },
                        horizontalArrangement = Arrangement.spacedBy(
                            MaterialTheme.spacing.extraSmall,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            receipt.merchant ?: "Unrecognized receipt",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Rename store",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Total and date are scanned figures and just as often wrong as the store
                    // line, so they carry the same tap-to-correct affordance.
                    Row(
                        modifier = Modifier.clickable { showSummaryDialog = true },
                        horizontalArrangement = Arrangement.spacedBy(
                            MaterialTheme.spacing.extraSmall,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatCurrency(receipt.totalCents, receipt.currency),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit date and total",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${formatDate(receipt.dateEpochMillis ?: receipt.createdAtEpochMillis)} · " +
                            "${receipt.items.size} ${if (receipt.items.size == 1) "item" else "items"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { showSummaryDialog = true },
                    )
                }
            }
            if (receipt.status == ReceiptStatus.PROCESSING) {
                item(key = "processing") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Still processing this receipt…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(MaterialTheme.spacing.medium),
                        )
                    }
                }
            }
            if (receipt.items.isNotEmpty()) {
                item(key = "import-status") {
                    ImportStatusCard(
                        importedCount = importedCount,
                        notImportedCount = notImportedCount,
                        onClick = onManageItems,
                        modifier = Modifier.padding(bottom = MaterialTheme.spacing.medium),
                    )
                }
                item(key = "items-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Items",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showAddItemDialog = true }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                            Text("Add item")
                        }
                    }
                }
                items(receipt.items, key = ReceiptItem::id) { item ->
                    ReceiptItemRow(item, onClick = { editingItemId = item.id })
                }
            } else if (receipt.status != ReceiptStatus.PROCESSING) {
                item(key = "no-items") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(MaterialTheme.spacing.medium),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                        ) {
                            Text(
                                "No items recognized",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (hasImage) {
                                    "Open the receipt to rerun text recognition or adjust the image."
                                } else {
                                    "This receipt arrived without any line items."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(
                                    MaterialTheme.spacing.small,
                                ),
                            ) {
                                if (hasImage) {
                                    TextButton(onClick = onViewReceipt) {
                                        Text("View receipt")
                                    }
                                }
                                TextButton(onClick = { showAddItemDialog = true }) {
                                    Text("Add item")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameMerchantDialog(
            currentMerchant = receipt.merchant,
            suggestions = merchantSuggestions,
            onConfirm = { merchant ->
                showRenameDialog = false
                onRenameMerchant(merchant)
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showSummaryDialog) {
        EditReceiptSummaryDialog(
            // The stored date, not the created-at fallback the header prints: seeding the picker
            // with the scan time would turn "date not recognized" into a date the user never set.
            dateEpochMillis = receipt.dateEpochMillis,
            totalCents = receipt.totalCents,
            currency = receipt.currency,
            onConfirm = { date, total ->
                showSummaryDialog = false
                onEditSummary(date, total)
            },
            onDismiss = { showSummaryDialog = false },
        )
    }

    val editingItem = receipt.items.firstOrNull { it.id == editingItemId }
    if (editingItem != null) {
        EditReceiptItemDialog(
            item = editingItem,
            alreadyImported = editingItem.id in receipt.importedItemIds,
            onSave = {
                editingItemId = null
                onUpdateItem(it)
            },
            onDelete = {
                editingItemId = null
                onDeleteItem(editingItem.id)
            },
            onDismiss = { editingItemId = null },
        )
    } else {
        // The line went away underneath the dialog — deleted, or the receipt reloaded without it.
        // Cleared in an effect rather than inline: the id is read just above, and writing it
        // during composition would be a backwards write.
        LaunchedEffect(editingItemId) { editingItemId = null }
    }

    if (showAddItemDialog) {
        EditReceiptItemDialog(
            item = null,
            onSave = {
                showAddItemDialog = false
                onAddItem(it)
            },
            onDismiss = { showAddItemDialog = false },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
            title = { Text("Delete this receipt?") },
            text = {
                Text(
                    "The receipt, recognized text, and original captured sections will be " +
                        "permanently removed. Inventory items stay in place.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ImportStatusCard(
    importedCount: Int,
    notImportedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        ImportStatusRow(
            icon = {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            // Not "…to inventory": a line counted here may sit in another household, or have been
            // used up long ago. Imported is the only claim the count actually supports.
            label = "Imported",
            count = importedCount,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
        ImportStatusRow(
            icon = {
                Icon(
                    Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            },
            label = "Not imported",
            count = notImportedCount,
        )
    }
}

@Composable
private fun ImportStatusRow(
    icon: @Composable () -> Unit,
    label: String,
    count: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.medium,
                vertical = MaterialTheme.spacing.small + MaterialTheme.spacing.extraSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        icon()
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count ${if (count == 1) "item" else "items"}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReceiptItemRow(item: ReceiptItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = MaterialTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(
                        resolveIconRes(item.foodConceptId, item.category, item.name),
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Text(
            // A unit-bearing line is a measured amount — EDEKA's weighed produce comes through as
            // 1.164 kg — so it prints as "×1.2 kg" and prints even below one, where a bare "×1.16"
            // would read as a piece count. Countable lines keep the plain multiplier.
            when {
                item.unit != null -> "${item.name} ×${formatAmount(item.quantity, item.unit)}"
                item.quantity > 1.0 -> "${item.name} ×${formatQuantity(item.quantity)}"
                else -> item.name
            },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatCurrency(item.priceCents),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // Same affordance the store name carries above: OCR line items are wrong often enough
        // that the correction path should be visible without hunting for it.
        Icon(
            Icons.Default.Edit,
            contentDescription = "Edit item",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
