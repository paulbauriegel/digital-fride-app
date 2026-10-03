package com.example.groceriesapp.feature.recipes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.example.groceriesapp.data.InventoryItem
import com.example.groceriesapp.data.isCountable
import com.example.groceriesapp.data.recipes.CookConsumption
import com.example.groceriesapp.data.recipes.CookPlanner
import com.example.groceriesapp.data.recipes.RecipeMatch
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.ui.components.StarRatingInput
import com.example.groceriesapp.ui.formatAmount
import com.example.groceriesapp.ui.formatQuantity
import com.example.groceriesapp.ui.theme.spacing

private class CookRowState(
    val item: InventoryItem,
    val defaultConsumption: CookConsumption,
    included: Boolean = true,
) {
    var consumption by androidx.compose.runtime.mutableStateOf(defaultConsumption)
    var included by androidx.compose.runtime.mutableStateOf(included)

    /** What "used some" maps back to after toggling away from "used up". */
    val someConsumption: CookConsumption
        get() = defaultConsumption as? CookConsumption.UseSome
            ?: CookPlanner.stepDown(item)
}

/**
 * Confirmation sheet for "I cooked this": one row per inventory item the recipe matched, prefilled
 * from [CookPlanner.defaultPlan], each editable or excludable before the plan is applied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookRecipeSheet(
    match: RecipeMatch,
    onDismiss: () -> Unit,
    onConfirm: (consumptions: List<CookConsumption>, rating: Int?) -> Unit,
) {
    var rating by rememberSaveable { mutableStateOf<Int?>(null) }
    val rows = remember(match) {
        val itemsById = match.ingredientMatches
            .flatMap { it.matchedItems }
            .associateBy { it.id }
        mutableStateListOf<CookRowState>().apply {
            CookPlanner.defaultPlan(match).forEach { consumption ->
                itemsById[consumption.itemId]?.let { add(CookRowState(it, consumption)) }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // One row per ingredient, with the confirm button below them: a long recipe
                // would otherwise push the primary action past the bottom of the sheet.
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MaterialTheme.spacing.medium,
                    end = MaterialTheme.spacing.medium,
                    bottom = MaterialTheme.spacing.extraLarge,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(
                text = "Update inventory",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Cooked “${match.recipe.title}” — this is what gets used up:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rows.isEmpty()) {
                Text(
                    text = "No matching inventory items to update.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.medium),
                )
            }
            rows.forEach { row ->
                CookRow(row)
            }
            Text(
                text = "How was it? (optional)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = MaterialTheme.spacing.small),
            )
            StarRatingInput(value = rating, onChange = { rating = it })
            // Logging the cook is worth doing even when nothing in the inventory matched, so the
            // button no longer waits for at least one included row.
            Button(
                onClick = {
                    onConfirm(rows.filter { it.included }.map { it.consumption }, rating)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MaterialTheme.spacing.small),
            ) {
                Text(if (rows.any { it.included }) "Cooked · update inventory" else "Cooked")
            }
        }
    }
}

@Composable
private fun CookRow(row: CookRowState) {
    val item = row.item
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Checkbox(
            checked = row.included,
            onCheckedChange = { row.included = it },
        )
        Image(
            painter = painterResource(resolveIconRes(item.foodConceptId, item.category, item.name)),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = row.consumption.describe(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (val consumption = row.consumption) {
            is CookConsumption.Decrement -> if (item.isCountable) {
                IconButton(
                    onClick = {
                        row.consumption = consumption.copy(
                            amount = (consumption.amount - 1.0).coerceAtLeast(1.0),
                        )
                    },
                    enabled = consumption.amount > 1.0,
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Use one less")
                }
                Text(
                    text = formatQuantity(consumption.amount),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(
                    onClick = {
                        row.consumption = consumption.copy(
                            amount = (consumption.amount + 1.0).coerceAtMost(item.quantity),
                        )
                    },
                    enabled = consumption.amount < item.quantity,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Use one more")
                }
            }
            is CookConsumption.UseSome, is CookConsumption.RemoveAll -> {
                val usedUp = consumption is CookConsumption.RemoveAll
                FilterChip(
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    selected = !usedUp,
                    onClick = { row.consumption = row.someConsumption },
                    label = { Text("Some") },
                )
                FilterChip(
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    selected = usedUp,
                    onClick = { row.consumption = CookConsumption.RemoveAll(item.id) },
                    label = { Text("All") },
                )
            }
        }
    }
}

private fun CookConsumption.describe(item: InventoryItem): String = when (this) {
    is CookConsumption.Decrement -> {
        val unit = item.unit?.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
        "Use ${formatQuantity(amount)}$unit of ${formatQuantity(item.quantity)}$unit"
    }
    is CookConsumption.UseSome -> "Leave ${formatAmount(newQuantity, item.unit)}"
    is CookConsumption.RemoveAll -> "Remove from inventory"
}
