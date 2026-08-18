package com.example.groceriesapp.feature.inventory

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.ConsumptionReason
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.fillFraction
import com.example.groceriesapp.data.household.Household
import com.example.groceriesapp.data.taxonomy.ContainerType
import com.example.groceriesapp.data.taxonomy.ExpirySource
import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import com.example.groceriesapp.data.taxonomy.GroceryCategory
import com.example.groceriesapp.data.taxonomy.StorageLocation
import com.example.groceriesapp.data.taxonomy.resolveCategory
import com.example.groceriesapp.data.taxonomy.resolveContainerType
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.ui.amountTickLabels
import com.example.groceriesapp.ui.color
import com.example.groceriesapp.ui.components.ContainerFillGauge
import com.example.groceriesapp.ui.datePickerMillisToLocal
import com.example.groceriesapp.ui.expiryUrgency
import com.example.groceriesapp.ui.formatAmount
import com.example.groceriesapp.ui.formatDate
import com.example.groceriesapp.ui.formatExpiry
import com.example.groceriesapp.ui.localMillisToDatePicker
import com.example.groceriesapp.ui.shortLabel
import com.example.groceriesapp.ui.theme.spacing
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything about one inventory item on a single screen: what it is, where it's kept, when it
 * goes off, and how much is left.
 *
 * **Every control applies immediately** — there is no Save button. Each one maps to a single-field
 * repository mutator with an obvious undo (change it back), and the screen is reached by tapping a
 * row rather than by an explicit "create" intent, so a draft-and-commit model would only add an
 * unsaved-changes state and a discard prompt. `RecipeEditorScreen` saves explicitly because a
 * recipe is invalid until several fields agree; nothing here is cross-validated. The two typed
 * values (name, exact amount) commit through small dialogs, which is what the pencil affordances
 * open.
 *
 * The item is passed in fresh on every recomposition rather than copied into local state, so the
 * repository stays the single source of truth even while a cook flow or a household switch is
 * writing to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditInventoryItemScreen(
    item: InventoryItem,
    otherHouseholds: List<Household>,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onSetCategory: (GroceryCategory) -> Unit,
    onSetConcept: (String) -> Unit,
    onSetLocation: (StorageLocation) -> Unit,
    onSetExpiryDate: (Long?) -> Unit,
    onSetAmount: (Double) -> Unit,
    onSetPackageSize: (Double?) -> Unit,
    onMarkOpened: () -> Unit,
    onRemove: (ConsumptionReason?) -> Unit,
    onMoveToHousehold: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }
    var showConceptPicker by rememberSaveable { mutableStateOf(false) }
    var showHouseholdPicker by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showAmountDialog by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Edit item") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRemoveDialog = true }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Remove ${item.name}",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MaterialTheme.spacing.medium,
                    end = MaterialTheme.spacing.medium,
                    bottom = MaterialTheme.spacing.threeExtraLarge,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            ItemHeader(item = item, onRenameRequested = { showRenameDialog = true })

            SectionLabel("Category")
            CategoryDropdown(
                current = resolveCategory(item.category, item.name),
                onPick = onSetCategory,
            )
            // The concept is a separate, stronger claim than the bucket: it teaches the app what
            // this product *is*, everywhere. The picker's own docs ask the caller to say so.
            TextButton(onClick = { showConceptPicker = true }) {
                Text("Not the right product? Tell the app what this is")
            }

            SectionLabel("Stored in")
            StorageLocationRow(item = item, onSetLocation = onSetLocation)

            SectionLabel("Expiry date")
            ExpirySection(
                item = item,
                onOpenPicker = { showDatePicker = true },
                onClearDate = { onSetExpiryDate(null) },
            )

            SectionLabel("Amount left")
            AmountSection(
                item = item,
                onSetAmount = onSetAmount,
                onStartTracking = { onSetPackageSize(item.quantity) },
                onEditExactly = { showAmountDialog = true },
            )

            if (otherHouseholds.isNotEmpty()) {
                SectionLabel("Household")
                HouseholdRow(onClick = { showHouseholdPicker = true })
            }

            Spacer(Modifier.size(MaterialTheme.spacing.small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                if (item.openedAtEpochMillis == null) {
                    OutlinedButton(onClick = onMarkOpened, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                        Text("Mark as opened")
                    }
                }
                OutlinedButton(
                    onClick = { showRemoveDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
                    Text("Remove item")
                }
            }
        }
    }

    if (showConceptPicker) {
        FoodConceptPickerDialog(
            currentConceptId = item.foodConceptId,
            onPick = { concept ->
                onSetConcept(concept.id)
                showConceptPicker = false
            },
            onDismiss = { showConceptPicker = false },
        )
    }

    if (showRenameDialog) {
        TextEntryDialog(
            title = "Rename item",
            label = "Name",
            initial = item.name,
            supporting = "A name the app recognises re-files the item; otherwise it keeps its " +
                "current one. The link to the receipt it came from is kept either way.",
            onConfirm = {
                onRename(it)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showAmountDialog) {
        AmountEntryDialog(
            item = item,
            onConfirm = {
                onSetAmount(it)
                showAmountDialog = false
            },
            onStopTracking = {
                onSetPackageSize(null)
                showAmountDialog = false
            },
            onDismiss = { showAmountDialog = false },
        )
    }

    if (showDatePicker) {
        // The picker speaks UTC midnight; everything stored here is a local-zone instant.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = item.expiresAtEpochMillis?.let(::localMillisToDatePicker),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onSetExpiryDate(datePickerMillisToLocal(it)) }
                        showDatePicker = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) { Text("Set date") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showRemoveDialog) {
        RemoveItemChoiceDialog(
            itemName = item.name,
            onChoose = onRemove,
            onDismiss = { showRemoveDialog = false },
        )
    }

    if (showHouseholdPicker) {
        MoveToHouseholdDialog(
            itemName = item.name,
            households = otherHouseholds,
            onPick = { household ->
                showHouseholdPicker = false
                onMoveToHousehold(household.id)
            },
            onDismiss = { showHouseholdPicker = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun ItemHeader(item: InventoryItem, onRenameRequested: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Image(
                painter = painterResource(resolveIconRes(item.foodConceptId, item.category, item.name)),
                contentDescription = null,
                modifier = Modifier
                    .padding(MaterialTheme.spacing.medium)
                    .size(56.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(onClick = onRenameRequested) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Rename ${item.name}",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = buildString {
                    append(resolveCategory(item.category, item.name).displayName)
                    FoodConceptCatalog.byId(item.foodConceptId)?.let { append(" • ${it.displayName}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExpiryChip(item)
        }
    }
}

/** The clock pill from the mock: the expiry state in the fewest words that stay honest. */
@Composable
private fun ExpiryChip(item: InventoryItem) {
    val urgency = expiryUrgency(item)
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.small,
                vertical = MaterialTheme.spacing.extraSmall,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = urgency?.shortLabel() ?: formatExpiry(item),
                style = MaterialTheme.typography.labelMedium,
                color = urgency?.color() ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(current: GroceryCategory, onPick: (GroceryCategory) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = current.displayName,
            onValueChange = {},
            readOnly = true,
            leadingIcon = {
                Image(
                    painter = painterResource(current.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GroceryCategory.entries.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.displayName) },
                    leadingIcon = {
                        Image(
                            painter = painterResource(category.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        expanded = false
                        onPick(category)
                    },
                )
            }
        }
    }
}

@Composable
private fun StorageLocationRow(item: InventoryItem, onSetLocation: (StorageLocation) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        StorageLocation.entries.forEachIndexed { index, location ->
            SegmentedButton(
                modifier = Modifier.minimumInteractiveComponentSize(),
                selected = item.storageLocation == location.name,
                onClick = { onSetLocation(location) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = StorageLocation.entries.size,
                ),
                icon = {
                    Icon(
                        imageVector = when (location) {
                            StorageLocation.FRIDGE -> Icons.Default.Kitchen
                            StorageLocation.FREEZER -> Icons.Default.AcUnit
                            StorageLocation.PANTRY -> Icons.Default.Inventory2
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = { Text(location.displayName) },
            )
        }
    }
}

@Composable
private fun ExpirySection(
    item: InventoryItem,
    onOpenPicker: () -> Unit,
    onClearDate: () -> Unit,
) {
    val userSet = item.expiryEstimateSource == ExpirySource.USER.name
    val urgency = expiryUrgency(item)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPicker),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = item.expiresAtEpochMillis?.let(::formatDate) ?: "Set a date",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (urgency != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = urgency.shortLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        color = urgency.color(),
                        modifier = Modifier.padding(
                            horizontal = MaterialTheme.spacing.small,
                            vertical = MaterialTheme.spacing.extraSmall,
                        ),
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    Text(
        text = when {
            // Saying "moving re-estimates this" would be a lie for a date the user typed, and the
            // repository genuinely leaves those alone now.
            userSet -> "You set this date, so moving the item between fridge, freezer and pantry " +
                "won't change it."
            item.storageLocation != null -> "Moving an item re-estimates its expiry from today."
            else -> formatExpiry(item)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (userSet) {
        // A sticky date would otherwise be a one-way door.
        TextButton(onClick = onClearDate) { Text("Reset to estimate") }
    }
}

@Composable
private fun AmountSection(
    item: InventoryItem,
    onSetAmount: (Double) -> Unit,
    onStartTracking: () -> Unit,
    onEditExactly: () -> Unit,
) {
    val packageSize = item.packageSize
    if (packageSize == null) {
        // Very common: a bare receipt line's unit is unknown, so there is no scale to draw
        // against. Show what we do know and offer to start tracking rather than an empty gauge.
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text(
                    formatAmount(item.quantity, item.unit),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "This item isn't tracking how much is left.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onStartTracking) { Text("Track how much is left") }
            }
        }
        return
    }

    val container = resolveContainerType(item)
    // Local during the drag: persistInventory rewrites the whole index atomically, so a write per
    // frame would be the worst thing on this screen. One write on release.
    var dragFraction by remember(item.id, item.quantity, packageSize) {
        mutableStateOf<Float?>(null)
    }
    val fraction = dragFraction ?: item.fillFraction ?: 1f
    val shownAmount = if (dragFraction != null) packageSize * fraction else item.quantity

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            ) {
                ContainerFillGauge(
                    container = container,
                    fillFraction = fraction,
                    tickLabels = amountTickLabels(packageSize, item.unit),
                    contentDescription = "${formatAmount(shownAmount, item.unit)} left of " +
                        formatAmount(packageSize, item.unit),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatAmount(shownAmount, item.unit),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(onClick = onEditExactly) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Set the exact amount",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Text(
                        "of ${formatAmount(packageSize, item.unit)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = fraction,
                        onValueChange = { dragFraction = it },
                        onValueChangeFinished = {
                            dragFraction?.let { onSetAmount(packageSize * it) }
                            dragFraction = null
                        },
                    )
                    AmountPresets(
                        container = container,
                        packageSize = packageSize,
                        unit = item.unit,
                        currentFraction = fraction,
                        onSetAmount = onSetAmount,
                    )
                }
            }
        }
    }
}

/**
 * Quick-set chips. Loose goods get absolute weights because that is how you buy and think about
 * them ("a kilo of potatoes"); anything in a sealed pack gets proportions, because a quarter of a
 * pack means something and "0.5 of a carton" does not.
 */
@Composable
private fun AmountPresets(
    container: ContainerType,
    packageSize: Double,
    unit: String?,
    currentFraction: Float,
    onSetAmount: (Double) -> Unit,
) {
    val presets: List<Pair<String, Double>> = if (container == ContainerType.BULK) {
        (1..4).map { step ->
            val amount = packageSize * step / 4.0
            formatAmount(amount, unit) to amount
        }
    } else {
        listOf(0.25, 0.5, 0.75, 1.0).map { fraction ->
            "${(fraction * 100).roundToInt()}%" to packageSize * fraction
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
        presets.forEach { (label, amount) ->
            FilterChip(
                modifier = Modifier.minimumInteractiveComponentSize(),
                selected = abs(currentFraction - (amount / packageSize).toFloat()) < 0.01f,
                onClick = { onSetAmount(amount) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun HouseholdRow(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Move to another household", modifier = Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TextEntryDialog(
    title: String,
    label: String,
    initial: String,
    supporting: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                )
                supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AmountEntryDialog(
    item: InventoryItem,
    onConfirm: (Double) -> Unit,
    onStopTracking: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(formatAmountForEditing(item.quantity)) }
    // Same parse the add-item dialog uses: a comma is a decimal point in German.
    val parsed = value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Amount left") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(item.unit?.takeIf(String::isNotBlank) ?: "Amount") },
                    singleLine = true,
                    isError = value.isNotBlank() && parsed == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                )
                TextButton(onClick = onStopTracking) { Text("Stop tracking the amount") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatAmountForEditing(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
