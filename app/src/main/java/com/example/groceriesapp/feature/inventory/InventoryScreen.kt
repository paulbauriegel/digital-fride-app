package com.example.groceriesapp.feature.inventory

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import com.example.groceriesapp.data.ConsumptionReason
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import com.example.groceriesapp.data.taxonomy.GroceryCategory
import com.example.groceriesapp.data.fillFraction
import com.example.groceriesapp.data.isCountable
import com.example.groceriesapp.data.taxonomy.StorageLocation
import com.example.groceriesapp.data.taxonomy.resolveCategory
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.data.household.Household
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.components.GroceriesSearchBar
import com.example.groceriesapp.ui.components.rememberGroceriesSearchState
import com.example.groceriesapp.ui.components.RemainingAmountRing
import com.example.groceriesapp.ui.expiryUrgency
import com.example.groceriesapp.ui.formatAmount
import com.example.groceriesapp.ui.formatQuantity
import com.example.groceriesapp.ui.shortLabel
import com.example.groceriesapp.ui.theme.spacing
import com.example.groceriesapp.ui.theme.storageLocationColors
import com.example.groceriesapp.ui.color
import kotlinx.coroutines.launch

private enum class InventorySortOrder(val label: String) {
    RECENTLY_ADDED("Recently added"),
    EXPIRY("Expiry date"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    inventory: List<InventoryItem>,
    locationRequest: StorageLocation?,
    onLocationRequestConsumed: () -> Unit,
    onAddItem: (InventoryItem) -> Unit,
    onScanBarcode: () -> Unit,
    onRemoveItem: (String, ConsumptionReason?) -> Unit,
    onEditItem: (String) -> Unit,
    onAdjustQuantity: (String, Double) -> Unit,
    onUndoRemove: (InventoryItem) -> Unit,
    /**
     * A one-shot note to show once the list is back on screen — currently only "moved to X" after
     * the edit screen sends an item to another household. That row vanishes from this list, so
     * without a word here it would just look like it disappeared.
     */
    pendingMessage: String?,
    onPendingMessageConsumed: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchState = rememberGroceriesSearchState()
    var categoryFiltersOpen by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf<GroceryCategory?>(null) }
    var locationFilter by rememberSaveable { mutableStateOf<StorageLocation?>(null) }
    var sortOrder by rememberSaveable { mutableStateOf(InventorySortOrder.EXPIRY) }
    var showAddChoice by rememberSaveable { mutableStateOf(false) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var removeItemId by rememberSaveable { mutableStateOf<String?>(null) }

    // Home's inventory-summary cards land here pre-filtered to one location.
    LaunchedEffect(locationRequest) {
        if (locationRequest != null) {
            locationFilter = locationRequest
            onLocationRequestConsumed()
        }
    }
    // The chips drive the main list; the query only narrows the search view, so the list does not
    // re-filter itself behind the overlay.
    val filtered = remember(inventory, category, locationFilter, sortOrder) {
        val matching = inventory.filter { item ->
            (category == null || resolveCategory(item.category, item.name) == category) &&
                (locationFilter == null || item.storageLocation == locationFilter?.name)
        }
        when (sortOrder) {
            // The repository already delivers newest-first.
            InventorySortOrder.RECENTLY_ADDED -> matching
            InventorySortOrder.EXPIRY -> matching.sortedWith(
                compareBy<InventoryItem, Long?>(nullsLast()) { it.expiresAtEpochMillis },
            )
        }
    }

    val searchQuery = searchState.query
    val searchResults = remember(filtered, searchQuery) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            filtered.filter { item -> item.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    // Pinned rather than collapsing: the bar carries the search/filter actions, so it
    // stays put and only picks up the scrolled container colour that marks the boundary.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // A swipe removes immediately and offers the way back, rather than interrupting with a
    // dialog — the same bargain Gmail makes. Undo restores the row verbatim and un-logs the
    // consumption event, so a mis-swipe leaves the waste figures untouched.
    fun removeWithUndo(item: InventoryItem, reason: ConsumptionReason) {
        onRemoveItem(item.id, reason)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val action = snackbarHostState.showSnackbar(
                message = when (reason) {
                    ConsumptionReason.USED_UP -> "${item.name} used up"
                    ConsumptionReason.THROWN_AWAY -> "${item.name} thrown away"
                    ConsumptionReason.COOKED -> "${item.name} removed"
                },
                actionLabel = "Undo",
                withDismissAction = true,
                // Long, not Short: Short's 4s is not enough time to notice a mis-swipe, read the
                // name, and reach for Undo before the only way back disappears.
                duration = SnackbarDuration.Long,
            )
            if (action == SnackbarResult.ActionPerformed) onUndoRemove(item)
        }
    }

    LaunchedEffect(pendingMessage) {
        val message = pendingMessage ?: return@LaunchedEffect
        onPendingMessageConsumed()
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Inventory") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { scope.launch { searchState.expand() } }) {
                        Icon(Icons.Default.Search, contentDescription = "Search inventory")
                    }
                    IconButton(onClick = { categoryFiltersOpen = !categoryFiltersOpen }) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = "Filter by category",
                            tint = if (category == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    IconButton(onClick = { showAddChoice = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add item")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = MaterialTheme.spacing.medium,
                        end = MaterialTheme.spacing.medium,
                        top = MaterialTheme.spacing.small,
                    ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                val options = listOf<StorageLocation?>(null) + StorageLocation.entries
                options.forEach { option ->
                    FilterChip(
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        selected = locationFilter == option,
                        onClick = { locationFilter = option },
                        label = { Text(option?.displayName ?: "All") },
                    )
                }
            }
            locationFilter?.let { location ->
                LocationHeaderCard(
                    location = location,
                    count = inventory.count { it.storageLocation == location.name },
                    onClick = { locationFilter = null },
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.small,
                    ),
                )
            }
            AnimatedVisibility(visible = categoryFiltersOpen) {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.small,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    item {
                        FilterChip(
                            modifier = Modifier.minimumInteractiveComponentSize(),
                            selected = category == null,
                            onClick = { category = null },
                            label = { Text("All") },
                        )
                    }
                    items(GroceryCategory.entries) { option ->
                        FilterChip(
                            modifier = Modifier.minimumInteractiveComponentSize(),
                            selected = category == option,
                            onClick = { category = if (category == option) null else option },
                            label = { Text(option.displayName) },
                        )
                    }
                }
            }

            if (inventory.isEmpty()) {
                EmptyStateCard(
                    title = "Your fridge is ready",
                    body = "Scan a receipt to review and add groceries, " +
                        "or add an item by barcode or by hand.",
                    actionLabel = "Add item",
                    onAction = { showAddChoice = true },
                    icon = Icons.Default.Inventory2,
                    modifier = Modifier.padding(MaterialTheme.spacing.medium),
                )
            } else if (filtered.isEmpty()) {
                EmptyStateCard(
                    title = "No matching groceries",
                    body = "Try another category or location.",
                    actionLabel = "Clear filters",
                    onAction = {
                        category = null
                        locationFilter = null
                    },
                    icon = Icons.Default.Search,
                    modifier = Modifier.padding(MaterialTheme.spacing.medium),
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = MaterialTheme.spacing.medium,
                        end = MaterialTheme.spacing.medium,
                        bottom = MaterialTheme.spacing.medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${filtered.size} ${if (filtered.size == 1) "item" else "items"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            var sortMenuOpen by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { sortMenuOpen = true }) {
                                    Icon(
                                        Icons.Default.SwapVert,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "Sort by: ${sortOrder.label}",
                                        modifier = Modifier.padding(
                                            start = MaterialTheme.spacing.extraSmall,
                                        ),
                                    )
                                }
                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false },
                                ) {
                                    InventorySortOrder.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                sortOrder = option
                                                sortMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    items(filtered, key = InventoryItem::id) { item ->
                        SwipeableInventoryRow(
                            item = item,
                            onClick = { onEditItem(item.id) },
                            onIncrement = { onAdjustQuantity(item.id, 1.0) },
                            onDecrement = { onAdjustQuantity(item.id, -1.0) },
                            onDelete = { removeItemId = item.id },
                            onUsedUp = { removeWithUndo(item, ConsumptionReason.USED_UP) },
                            onThrownAway = { removeWithUndo(item, ConsumptionReason.THROWN_AWAY) },
                        )
                    }
                }
            }
        }
    }

    GroceriesSearchBar(
        state = searchState,
        placeholder = "Search your fridge",
    ) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            items(searchResults, key = InventoryItem::id) { item ->
                InventoryRow(
                    item = item,
                    onClick = {
                        scope.launch { searchState.collapse() }
                        onEditItem(item.id)
                    },
                    onIncrement = { onAdjustQuantity(item.id, 1.0) },
                    onDecrement = { onAdjustQuantity(item.id, -1.0) },
                    onDelete = { removeItemId = item.id },
                )
            }
        }
    }

    if (showAddChoice) {
        AddItemMethodSheet(
            onAddManually = { showAddDialog = true },
            onScanBarcode = onScanBarcode,
            onDismiss = { showAddChoice = false },
        )
    }

    if (showAddDialog) {
        AddInventoryItemDialog(
            onDismiss = { showAddDialog = false },
            onSave = {
                onAddItem(it)
                showAddDialog = false
            },
        )
    }

    val removeItem = removeItemId?.let { id -> inventory.firstOrNull { it.id == id } }
    LaunchedEffect(removeItemId, removeItem) {
        if (removeItemId != null && removeItem == null) removeItemId = null
    }
    if (removeItem != null) {
        RemoveItemChoiceDialog(
            itemName = removeItem.name,
            onChoose = { reason ->
                onRemoveItem(removeItem.id, reason)
                removeItemId = null
            },
            onDismiss = { removeItemId = null },
        )
    }
}

@Composable
private fun LocationHeaderCard(
    location: StorageLocation,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.storageLocationColors.containerFor(location),
            contentColor = MaterialTheme.storageLocationColors.onContainerFor(location),
        ),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(location.emojiFallback, style = MaterialTheme.typography.titleLarge)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    location.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "$count ${if (count == 1) "item" else "items"}",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableInventoryRow(
    item: InventoryItem,
    onClick: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onDelete: () -> Unit,
    onUsedUp: () -> Unit,
    onThrownAway: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onUsedUp()
                SwipeToDismissBoxValue.EndToStart -> onThrownAway()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // Never accept the dismissed state. SwipeToDismissBoxState saves through
            // rememberSaveable and LazyColumn keys that store by item id, so an undone row —
            // which comes back with the same id — would be restored still swiped open, showing
            // the bare action background. The row leaves the list because the item is genuinely
            // removed, not because the box holds a dismissed state.
            false
        },
        // Half the row, so a glancing sideways drag while scrolling never removes anything.
        positionalThreshold = { distance -> distance * 0.5f },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
    ) {
        InventoryRow(
            item = item,
            onClick = onClick,
            onIncrement = onIncrement,
            onDecrement = onDecrement,
            onDelete = onDelete,
        )
    }
}

/**
 * The colour and label revealed under a swiping row: used up leans on primary, thrown away on
 * error, and each sits on the edge the finger came from so the meaning is legible mid-gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val usedUp = direction == SwipeToDismissBoxValue.StartToEnd
    val container = if (usedUp) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (usedUp) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(CardDefaults.shape)
            .background(container)
            .padding(horizontal = MaterialTheme.spacing.large),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (usedUp) Arrangement.Start else Arrangement.End,
    ) {
        Icon(
            imageVector = if (usedUp) Icons.Default.CheckCircle else Icons.Default.DeleteOutline,
            contentDescription = null,
            tint = content,
        )
        Text(
            text = if (usedUp) "Used up" else "Thrown away",
            style = MaterialTheme.typography.labelLarge,
            color = content,
            modifier = Modifier.padding(start = MaterialTheme.spacing.small),
        )
    }
}

@Composable
private fun InventoryRow(
    item: InventoryItem,
    onClick: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MaterialTheme.spacing.medium,
                    end = MaterialTheme.spacing.small,
                    top = MaterialTheme.spacing.small + MaterialTheme.spacing.extraSmall,
                    bottom = MaterialTheme.spacing.small + MaterialTheme.spacing.extraSmall,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(
                            resolveIconRes(item.foodConceptId, item.category, item.name),
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(formatQuantity(item.quantity))
                        item.unit?.takeIf(String::isNotBlank)?.let { append(" $it") }
                        if (item.openedAtEpochMillis != null) append(" \u00b7 Opened")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val urgency = expiryUrgency(item)
                if (urgency != null) {
                    Text(
                        urgency.shortLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = urgency.color(),
                    )
                }
            }
            RemainingAmountRing(
                fraction = item.fillFraction,
                label = "${formatAmount(item.quantity, item.unit)} left",
            )
            if (item.isCountable) {
                IconButton(onClick = onIncrement) {
                    Icon(Icons.Default.Add, contentDescription = "One ${item.name} more")
                }
            }
            // On the last piece the bin takes the minus slot, so counting down naturally ends in
            // the removal question.
            if (item.isCountable && item.quantity > 1.0) {
                IconButton(onClick = onDecrement) {
                    Icon(Icons.Default.Remove, contentDescription = "One ${item.name} less")
                }
            } else {
                IconButton(onClick = onDelete) {
                    // Matches the used-up swipe rather than a bin: the dialog this opens now
                    // treats a plain removal as eaten, and a bin icon would suggest waste.
                    Icon(Icons.Default.CheckCircle, contentDescription = "Remove ${item.name}")
                }
            }
        }
    }
}

/**
 * What a barcode scan knows about an item before the user confirms it. A [barcode] with everything
 * else empty is the normal shape when Open Food Facts has never heard of the pack.
 */
internal data class InventoryItemPrefill(
    val barcode: String,
    val name: String = "",
    val quantity: Double? = null,
    val unit: String? = null,
)

@Composable
internal fun AddInventoryItemDialog(
    onDismiss: () -> Unit,
    onSave: (InventoryItem) -> Unit,
    prefill: InventoryItemPrefill? = null,
    notice: String? = null,
) {
    // Keyed on the barcode so scanning a second pack without leaving the screen starts clean
    // instead of keeping the previous product's name.
    var name by rememberSaveable(prefill?.barcode) { mutableStateOf(prefill?.name.orEmpty()) }
    var quantity by rememberSaveable(prefill?.barcode) {
        mutableStateOf(prefill?.quantity?.let(::formatQuantity) ?: "1")
    }
    // No unit is prefilled — "pc" would silently mislabel weight-packed goods.
    var unit by rememberSaveable(prefill?.barcode) { mutableStateOf(prefill?.unit.orEmpty()) }
    // Derived from the name until the user picks a chip, so typing "Vollmilch" lands in Dairy.
    var chosenCategory by rememberSaveable(prefill?.barcode) {
        mutableStateOf<GroceryCategory?>(null)
    }
    val concept = remember(name) { FoodConceptCatalog.match(name) }
    val category = chosenCategory ?: concept?.category ?: GroceryCategory.OTHER
    val focusManager = LocalFocusManager.current

    val parsedQuantity = quantity.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    // Only flag typed-in nonsense; an emptied field is on its way somewhere, not yet wrong.
    val quantityInvalid = quantity.isNotBlank() && parsedQuantity == null
    val canSave = name.isNotBlank() && parsedQuantity != null
    // Shared by the IME "Done" action and the Save button.
    fun save() {
        val amount = parsedQuantity ?: return
        onSave(
            InventoryItem(
                name = name.trim(),
                quantity = amount,
                unit = unit.trim().ifBlank { null },
                category = category.name,
                foodConceptId = concept?.id.takeIf { category == concept?.category },
                barcode = prefill?.barcode,
                // A scanned pack starts out full, so what it holds is what Open Food Facts
                // reported — but only while the unit is still the one that figure was given in.
                packageSize = prefill?.quantity
                    ?.takeIf { unit.trim().equals(prefill.unit, ignoreCase = true) },
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (prefill != null) Icons.Default.QrCodeScanner else Icons.Default.Inventory2,
                contentDescription = null,
            )
        },
        title = { Text(if (prefill != null) "Confirm scanned item" else "Add item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                notice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Quantity") },
                        isError = quantityInvalid,
                        supportingText = if (quantityInvalid) {
                            {
                                Text(
                                    "Enter a number above 0.",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Unit") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("Category", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    items(GroceryCategory.entries) { option ->
                        FilterChip(
                            modifier = Modifier.minimumInteractiveComponentSize(),
                            selected = category == option,
                            onClick = { chosenCategory = option },
                            label = { Text(option.displayName) },
                        )
                    }
                }
                Text(
                    "Expiry is estimated from typical storage times, not from the packaging. " +
                        "Always check the printed date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = { save() },
            ) {
                Text("Save item")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
