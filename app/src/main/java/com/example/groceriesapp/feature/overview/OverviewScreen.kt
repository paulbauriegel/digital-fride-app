package com.example.groceriesapp.feature.overview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.DigitalReceipt
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.recipes.Recipe
import com.example.groceriesapp.data.recipes.RecipeFilters
import com.example.groceriesapp.data.recipes.RecipeMatch
import com.example.groceriesapp.data.recipes.RecipeMatcher
import com.example.groceriesapp.data.taxonomy.StorageLocation
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.feature.inventory.AddInventoryItemDialog
import com.example.groceriesapp.feature.recipes.RecipeImage
import com.example.groceriesapp.ui.components.EmptyStateCard
import com.example.groceriesapp.ui.components.SectionHeader
import com.example.groceriesapp.ui.expiresSoon
import com.example.groceriesapp.ui.expiryUrgency
import com.example.groceriesapp.ui.shortLabel
import com.example.groceriesapp.ui.theme.spacing
import com.example.groceriesapp.ui.theme.storageLocationColors
import com.example.groceriesapp.ui.color
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Mockup view 1 — the Home dashboard: time-of-day greeting, "Use soon", a recipe suggestion tied
 * to the expiring items, quick actions, and per-location inventory summaries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    userName: String?,
    inventory: List<InventoryItem>,
    receipts: List<DigitalReceipt>,
    recipes: List<Recipe>,
    favoriteRecipeIds: Set<String>,
    imagesDir: File,
    imageVersion: Int,
    onScanReceipt: () -> Unit,
    onAddItem: (InventoryItem) -> Unit,
    onOpenInventory: (StorageLocation?) -> Unit,
    onOpenRecipes: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    val useSoon = remember(inventory) {
        inventory
            .filter { expiresSoon(it) }
            .sortedBy(InventoryItem::expiresAtEpochMillis)
            .take(4)
    }
    val suggestion = remember(inventory, recipes, favoriteRecipeIds) {
        RecipeMatcher()
            .sections(recipes, inventory, LocalDate.now(), RecipeFilters(), favoriteRecipeIds)
            .bestMatches
            .filter { it.expiringUsed >= 1 }
            .maxByOrNull { it.expiringUsed }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(greeting(userName))
                        Text(
                            "Here's what's happening in your kitchen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(
                start = MaterialTheme.spacing.medium,
                end = MaterialTheme.spacing.medium,
                bottom = MaterialTheme.spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            if (inventory.isEmpty() && receipts.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = "Turn paper receipts into your fridge",
                        body = "Record the receipt freely. We track the paper, keep only sharp " +
                            "overlapping views, and build one continuous receipt for review.",
                        actionLabel = "Scan first receipt",
                        onAction = onScanReceipt,
                        icon = Icons.Default.CameraAlt,
                    )
                }
                item {
                    FirstScanSteps()
                }
            } else {
                if (useSoon.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Use soon",
                            actionLabel = "See all",
                            onAction = { onOpenInventory(null) },
                        )
                    }
                    items(count = useSoon.size, key = { useSoon[it].id }) { index ->
                        UseSoonRow(
                            item = useSoon[index],
                            onClick = { onOpenInventory(null) },
                        )
                    }
                }
                suggestion?.let { match ->
                    item(key = "suggestion") {
                        SuggestedRecipeCard(
                            match = match,
                            imagesDir = imagesDir,
                            imageVersion = imageVersion,
                            onClick = { onOpenRecipe(match.recipe.id) },
                        )
                    }
                }
                item(key = "quick-actions-header") {
                    SectionHeader(title = "Quick actions")
                }
                item(key = "quick-actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                        QuickActionTile(
                            icon = Icons.Default.DocumentScanner,
                            label = "Scan receipt",
                            onClick = onScanReceipt,
                            modifier = Modifier.weight(1f),
                        )
                        QuickActionTile(
                            icon = Icons.Default.Add,
                            label = "Add item",
                            onClick = { showAddDialog = true },
                            modifier = Modifier.weight(1f),
                        )
                        QuickActionTile(
                            icon = Icons.Default.RestaurantMenu,
                            label = "Find recipes",
                            onClick = onOpenRecipes,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item(key = "inventory-summary-header") {
                    SectionHeader(
                        title = "Inventory summary",
                        actionLabel = "See all",
                        onAction = { onOpenInventory(null) },
                    )
                }
                item(key = "inventory-summary") {
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                        StorageLocation.entries.forEach { location ->
                            LocationSummaryCard(
                                location = location,
                                count = inventory.count { it.storageLocation == location.name },
                                onClick = { onOpenInventory(location) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddInventoryItemDialog(
            onDismiss = { showAddDialog = false },
            onSave = { item ->
                onAddItem(item)
                showAddDialog = false
            },
        )
    }
}

/** Time-of-day greeting; without a saved name it stays generic rather than guessing one. */
private fun greeting(userName: String?): String {
    val salutation = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    return if (userName.isNullOrBlank()) "$salutation 👋" else "$salutation, $userName 👋"
}

@Composable
private fun UseSoonRow(
    item: InventoryItem,
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
                item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val urgency = expiryUrgency(item)
            if (urgency != null) {
                Text(
                    urgency.shortLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = urgency.color(),
                )
            }
        }
    }
}

@Composable
private fun SuggestedRecipeCard(
    match: RecipeMatch,
    imagesDir: File,
    imageVersion: Int,
    onClick: () -> Unit,
) {
    val recipe = match.recipe
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            ) {
                Text(
                    "You can use ${match.expiringUsed} expiring " +
                        "${if (match.expiringUsed == 1) "item" else "items"} in:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    recipe.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${recipe.prepMinutes} min · ${recipe.servings} " +
                        "${if (recipe.servings == 1) "serving" else "servings"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            RecipeImage(
                recipe = recipe,
                imagesDir = imagesDir,
                imageVersion = imageVersion,
                modifier = Modifier
                    .size(96.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
        }
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(MaterialTheme.spacing.small)
                        .size(22.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LocationSummaryCard(
    location: StorageLocation,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.storageLocationColors.containerFor(location),
            contentColor = MaterialTheme.storageLocationColors.onContainerFor(location),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Text(location.emojiFallback, style = MaterialTheme.typography.titleLarge)
            Text(
                location.displayName,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "$count ${if (count == 1) "item" else "items"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.small,
                    vertical = MaterialTheme.spacing.extraSmall,
                ),
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
