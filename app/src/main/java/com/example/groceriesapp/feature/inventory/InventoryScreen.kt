package com.example.groceriesapp.feature.inventory

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import com.example.groceriesapp.data.taxonomy.GroceryCategory
import com.example.groceriesapp.data.taxonomy.resolveCategory
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.formatExpiry
import com.example.groceriesapp.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    inventory: List<InventoryItem>,
    onAddItem: (InventoryItem) -> Unit,
    onRemoveItem: (String) -> Unit,
    onMarkOpened: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<GroceryCategory?>(null) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(inventory, query, category) {
        inventory.filter { item ->
            (query.isBlank() || item.name.contains(query, ignoreCase = true)) &&
                (category == null || resolveCategory(item.category, item.name) == category)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Inventory") },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add item manually")
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                placeholder = { Text("Search your fridge") },
                singleLine = true,
                shape = RoundedCornerShape(percent = 50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.medium),
            )
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.small,
                ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                item {
                    FilterChip(
                        selected = category == null,
                        onClick = { category = null },
                        label = { Text("All") },
                    )
                }
                items(GroceryCategory.entries) { option ->
                    FilterChip(
                        selected = category == option,
                        onClick = { category = if (category == option) null else option },
                        label = { Text(option.displayName) },
                    )
                }
            }

            if (inventory.isEmpty()) {
                EmptyStateCard(
                    title = "Your fridge is ready",
                    body = "Scan a receipt to review and add groceries, or create an item manually.",
                    actionLabel = "Add item manually",
                    onAction = { showAddDialog = true },
                    icon = Icons.Default.Inventory2,
                    modifier = Modifier.padding(MaterialTheme.spacing.medium),
                )
            } else if (filtered.isEmpty()) {
                EmptyStateCard(
                    title = "No matching groceries",
                    body = "Try another search or category.",
                    actionLabel = "Clear filters",
                    onAction = {
                        query = ""
                        category = null
                    },
                    icon = Icons.Default.Search,
                    modifier = Modifier.padding(MaterialTheme.spacing.medium),
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = MaterialTheme.spacing.medium,
                        end = MaterialTheme.spacing.medium,
                        bottom = 112.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    item {
                        Text(
                            "${filtered.size} ${if (filtered.size == 1) "item" else "items"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = MaterialTheme.spacing.small),
                        )
                    }
                    items(filtered, key = InventoryItem::id) { item ->
                        InventoryRow(
                            item = item,
                            onRemove = { onRemoveItem(item.id) },
                            onMarkOpened = { onMarkOpened(item.id) },
                        )
                    }
                }
            }
        }
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
}

@Composable
private fun InventoryRow(
    item: InventoryItem,
    onRemove: () -> Unit,
    onMarkOpened: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Image(
                    painter = painterResource(
                        resolveIconRes(item.foodConceptId, item.category, item.name),
                    ),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(32.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = buildString {
                        append(formatQuantity(item.quantity))
                        item.unit?.takeIf(String::isNotBlank)?.let { append(" $it") }
                        append(" • ")
                        append(resolveCategory(item.category, item.name).displayName)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildString {
                        append(formatExpiry(item))
                        if (item.openedAtEpochMillis != null) append(" • opened")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.openedAtEpochMillis == null) {
                IconButton(onClick = onMarkOpened) {
                    Icon(
                        Icons.Default.LockOpen,
                        contentDescription = "Mark ${item.name} as opened",
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove ${item.name}")
            }
        }
    }
}

@Composable
private fun AddInventoryItemDialog(
    onDismiss: () -> Unit,
    onSave: (InventoryItem) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf("pc") }
    // Derived from the name until the user picks a chip, so typing "Vollmilch" lands in Dairy.
    var chosenCategory by rememberSaveable { mutableStateOf<GroceryCategory?>(null) }
    val concept = remember(name) { FoodConceptCatalog.match(name) }
    val category = chosenCategory ?: concept?.category ?: GroceryCategory.OTHER

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
        title = { Text("Add item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Quantity") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Unit") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("Category", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    items(GroceryCategory.entries) { option ->
                        FilterChip(
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
                enabled = name.isNotBlank() && quantity.replace(',', '.').toDoubleOrNull()
                    ?.let { it > 0 } == true,
                onClick = {
                    onSave(
                        InventoryItem(
                            name = name.trim(),
                            quantity = quantity.replace(',', '.').toDouble(),
                            unit = unit.trim().ifBlank { null },
                            category = category.name,
                            foodConceptId = concept?.id.takeIf { category == concept?.category },
                        ),
                    )
                },
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

private fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
