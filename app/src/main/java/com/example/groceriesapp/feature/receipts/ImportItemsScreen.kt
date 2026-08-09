package com.example.groceriesapp.feature.receipts

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.ImportDisposition
import com.example.groceriesapp.data.LocationSource
import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.data.ReceiptItemSelection
import com.example.groceriesapp.data.taxonomy.StorageLocation
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.formatCurrency
import com.example.groceriesapp.ui.formatDate
import com.example.groceriesapp.ui.theme.spacing

/**
 * Mockup view 6 — review a receipt's items and choose, per row, where each lands (or "Don't add").
 * The selection logic used to live inside the combined review screen; this screen owns it now,
 * while the receipt image tooling stays in
 * [com.example.groceriesapp.feature.review.ReceiptViewerScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportItemsScreen(
    receipt: DigitalReceipt,
    onBack: () -> Unit,
    onAddItemsToInventory: (List<ReceiptItemSelection>) -> Unit,
    suggestLocation: (ReceiptItem) -> StorageLocation,
    activeHouseholdId: String,
    householdName: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    val pendingItems = remember(receipt) { receipt.items.filter { it.id !in receipt.importedItemIds } }
    val importedItems = remember(receipt) { receipt.items.filter { it.id in receipt.importedItemIds } }
    // One map carries both checkbox and dropdown: null destination = "Don't add".
    var destinations by remember(receipt.id) {
        val defaults = receipt.items.defaultSelectionIds()
        mutableStateOf(
            receipt.items
                .filter { it.id !in receipt.importedItemIds }
                .associate { item ->
                    item.id to if (item.id in defaults) suggestLocation(item) else null
                },
        )
    }
    // Ids whose destination the user picked by hand import as USER so re-suggestion passes
    // leave them alone.
    var userSetLocationIds by remember(receipt.id) { mutableStateOf(emptySet<String>()) }
    val selectedCount = destinations.values.count { it != null }
    val allSelected = pendingItems.isNotEmpty() && selectedCount == pendingItems.size

    fun toggleItem(item: ReceiptItem) {
        val current = destinations[item.id]
        destinations = destinations +
            (item.id to if (current == null) suggestLocation(item) else null)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Import from ${receipt.merchant ?: "receipt"}")
                        Text(
                            "${formatDate(receipt.dateEpochMillis ?: receipt.createdAtEpochMillis)} · " +
                                "${receipt.items.size} ${if (receipt.items.size == 1) "item" else "items"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (pendingItems.isNotEmpty()) {
                Button(
                    onClick = {
                        val selections = destinations.mapNotNull { (id, location) ->
                            location?.let {
                                ReceiptItemSelection(
                                    receiptItemId = id,
                                    storageLocation = it,
                                    locationSource = if (id in userSetLocationIds) {
                                        LocationSource.USER
                                    } else {
                                        LocationSource.SUGGESTED
                                    },
                                )
                            }
                        }
                        onAddItemsToInventory(selections)
                        onBack()
                    },
                    enabled = selectedCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.spacing.medium)
                        .height(52.dp),
                ) {
                    Text(
                        "Add $selectedCount ${if (selectedCount == 1) "item" else "items"} to inventory",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
    ) { innerPadding ->
        if (pendingItems.isEmpty()) {
            // "…already in your inventory" would be a lie whenever another household took a line or
            // the goods are already gone, so the claim is narrowed to what the records support.
            val elsewhereOrGone = receipt.imports.any {
                it.disposition != ImportDisposition.IN_INVENTORY ||
                    it.householdId != activeHouseholdId
            }
            EmptyStateCard(
                title = "Nothing left to import",
                body = "All ${receipt.items.size} items from this receipt have already been " +
                    "imported." +
                    if (elsewhereOrGone) {
                        " Some are in another household or have already been used up."
                    } else {
                        ""
                    },
                actionLabel = "Done",
                onAction = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(MaterialTheme.spacing.medium),
            )
            return@Scaffold
        }
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
            item(key = "review-header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Review items",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            destinations = if (allSelected) {
                                destinations.mapValues { null }
                            } else {
                                pendingItems.associate { item ->
                                    item.id to (destinations[item.id] ?: suggestLocation(item))
                                }
                            }
                        },
                    ) {
                        Text(if (allSelected) "Clear all" else "Select all")
                    }
                }
            }
            items(pendingItems, key = ReceiptItem::id) { item ->
                ImportItemRow(
                    item = item,
                    destination = destinations[item.id],
                    onToggle = { toggleItem(item) },
                    onDestinationChanged = { location ->
                        destinations = destinations + (item.id to location)
                        if (location != null) {
                            userSetLocationIds = userSetLocationIds + item.id
                        }
                    },
                )
            }
            if (importedItems.isNotEmpty()) {
                item(key = "imported-header") {
                    Text(
                        "Already imported (${importedItems.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            top = MaterialTheme.spacing.medium,
                            bottom = MaterialTheme.spacing.extraSmall,
                        ),
                    )
                }
                items(importedItems, key = { "imported-${it.id}" }) { item ->
                    ImportItemRow(
                        item = item,
                        destination = null,
                        onToggle = null,
                        onDestinationChanged = null,
                        importStatus = importStatusLabel(
                            record = receipt.importOf(item.id),
                            activeHouseholdId = activeHouseholdId,
                            householdName = householdName,
                        ),
                        modifier = Modifier.alpha(0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportItemRow(
    item: ReceiptItem,
    destination: StorageLocation?,
    onToggle: (() -> Unit)?,
    onDestinationChanged: ((StorageLocation?) -> Unit)?,
    modifier: Modifier = Modifier,
    /** Why this line can no longer be imported; null means it still can be. */
    importStatus: String? = null,
) {
    val alreadyImported = importStatus != null
    var destinationMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = alreadyImported || destination != null,
            onCheckedChange = onToggle?.let { { _ -> it() } },
            enabled = !alreadyImported,
        )
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
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MaterialTheme.spacing.small),
        ) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatCurrency(item.priceCents) + importStatus?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!alreadyImported && onDestinationChanged != null) {
            Box {
                TextButton(onClick = { destinationMenuOpen = true }) {
                    Text(destination?.displayName ?: "Don't add")
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Change destination")
                }
                DropdownMenu(
                    expanded = destinationMenuOpen,
                    onDismissRequest = { destinationMenuOpen = false },
                ) {
                    StorageLocation.entries.forEach { location ->
                        DropdownMenuItem(
                            text = { Text(location.displayName) },
                            onClick = {
                                destinationMenuOpen = false
                                onDestinationChanged(location)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Don't add") },
                        onClick = {
                            destinationMenuOpen = false
                            onDestinationChanged(null)
                        },
                    )
                }
            }
        }
    }
}
