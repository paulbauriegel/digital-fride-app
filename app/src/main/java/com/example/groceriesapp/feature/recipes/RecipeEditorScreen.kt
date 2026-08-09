package com.example.groceriesapp.feature.recipes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.groceriesapp.data.recipes.Recipe
import com.example.groceriesapp.data.recipes.RecipeIngredient
import com.example.groceriesapp.data.recipes.RecipeSource
import com.example.groceriesapp.data.recipes.newRecipeId
import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import com.example.groceriesapp.data.taxonomy.resolveIconRes
import com.example.groceriesapp.ui.components.SectionHeader
import com.example.groceriesapp.ui.formatQuantity
import com.example.groceriesapp.ui.theme.spacing

private class IngredientEditState(
    name: String = "",
    amount: String = "",
    unit: String = "",
    optional: Boolean = false,
    val presetConceptId: String? = null,
    val presetName: String? = null,
) {
    var name by mutableStateOf(name)
    var amount by mutableStateOf(amount)
    var unit by mutableStateOf(unit)
    var optional by mutableStateOf(optional)

    /** Preset id survives while the name is untouched; otherwise re-resolve from the name. */
    fun resolvedConceptId(): String? =
        if (presetConceptId != null && name.trim() == presetName?.trim()) {
            presetConceptId
        } else {
            resolveConcept(name)
        }
}

/** German keyword match first (the catalogue's native tongue), then the English display names. */
private fun resolveConcept(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    FoodConceptCatalog.match(trimmed)?.let { return it.id }
    return FoodConceptCatalog.all.firstOrNull {
        it.displayName.equals(trimmed, ignoreCase = true)
    }?.id
}

/**
 * Create/edit form for user recipes; also the confirmation step for photo-imported drafts, which
 * arrive here prefilled via [initial] before anything is saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorScreen(
    initial: Recipe?,
    onSave: (Recipe) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    // A new recipe starts with an empty title; flagging it before the user has typed reads as an
    // error they caused, so the title error waits for the first edit.
    var titleEdited by remember { mutableStateOf(initial?.title != null) }
    var servings by remember { mutableStateOf(initial?.servings?.toString() ?: "2") }
    var prepMinutes by remember { mutableStateOf(initial?.prepMinutes?.toString() ?: "30") }
    var tags by remember { mutableStateOf(initial?.tags?.joinToString(", ").orEmpty()) }
    val ingredients = remember {
        initial?.ingredients?.map { ingredient ->
            IngredientEditState(
                name = ingredient.displayName,
                amount = ingredient.amount?.let(::formatQuantity).orEmpty(),
                unit = ingredient.unit.orEmpty(),
                optional = ingredient.optional,
                presetConceptId = ingredient.foodConceptId,
                presetName = ingredient.displayName,
            )
        }.orEmpty().toMutableStateList().apply { if (isEmpty()) add(IngredientEditState()) }
    }
    val steps = remember {
        initial?.steps.orEmpty().toMutableStateList().apply { if (isEmpty()) add("") }
    }

    fun buildRecipe(): Recipe? {
        val cleanTitle = title.trim().takeIf(String::isNotEmpty) ?: return null
        val minutes = prepMinutes.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        val servingCount = servings.trim().toIntOrNull()?.takeIf { it > 0 } ?: 2
        val cleanIngredients = ingredients.mapNotNull { state ->
            val name = state.name.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            RecipeIngredient(
                foodConceptId = state.resolvedConceptId(),
                displayName = name,
                amount = state.amount.trim().replace(',', '.').toDoubleOrNull()
                    ?.takeIf { it > 0.0 },
                unit = state.unit.trim().takeIf(String::isNotEmpty),
                optional = state.optional,
            )
        }
        if (cleanIngredients.isEmpty()) return null
        val cleanSteps = steps.map(String::trim).filter(String::isNotEmpty)
        if (cleanSteps.isEmpty()) return null
        return Recipe(
            id = initial?.id?.takeIf { initial.source == RecipeSource.USER } ?: newRecipeId(),
            title = cleanTitle,
            ingredients = cleanIngredients,
            steps = cleanSteps,
            servings = servingCount,
            prepMinutes = minutes,
            tags = tags.split(',').map(String::trim).filter(String::isNotEmpty),
            imageRef = initial?.imageRef?.takeIf { initial.source == RecipeSource.USER },
            source = RecipeSource.USER,
            createdAtEpochMillis = initial?.createdAtEpochMillis ?: System.currentTimeMillis(),
        )
    }

    val candidate = buildRecipe()
    // The two fields buildRecipe() bails out on; a disabled Save button alone never says which.
    val titleInvalid = titleEdited && title.isBlank()
    val prepMinutesInvalid = prepMinutes.trim().toIntOrNull()?.let { it > 0 } != true

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (initial == null || initial.source != RecipeSource.USER) {
                            "New recipe"
                        } else {
                            "Edit recipe"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    titleEdited = true
                },
                label = { Text("Title") },
                singleLine = true,
                isError = titleInvalid,
                supportingText = if (titleInvalid) {
                    { Text("Give the recipe a title.", color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                OutlinedTextField(
                    value = servings,
                    onValueChange = { servings = it },
                    label = { Text("Servings") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = prepMinutes,
                    onValueChange = { prepMinutes = it },
                    label = { Text("Minutes") },
                    singleLine = true,
                    isError = prepMinutesInvalid,
                    supportingText = if (prepMinutesInvalid) {
                        { Text("Enter minutes above 0.", color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text("Tags (comma-separated)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(title = "Ingredients")
            ingredients.forEachIndexed { index, state ->
                IngredientEditor(
                    state = state,
                    onRemove = if (ingredients.size > 1) {
                        { ingredients.removeAt(index) }
                    } else {
                        null
                    },
                )
            }
            OutlinedButton(onClick = { ingredients.add(IngredientEditState()) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(
                    "Add ingredient",
                    modifier = Modifier.padding(start = MaterialTheme.spacing.small),
                )
            }

            SectionHeader(title = "Steps")
            steps.forEachIndexed { index, step ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    OutlinedTextField(
                        value = step,
                        onValueChange = { steps[index] = it },
                        label = { Text("Step ${index + 1}") },
                        // Steps are prose: give them room to grow without swallowing the page.
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.weight(1f),
                    )
                    if (steps.size > 1) {
                        IconButton(onClick = { steps.removeAt(index) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove step")
                        }
                    }
                }
            }
            OutlinedButton(onClick = { steps.add("") }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Add step", modifier = Modifier.padding(start = MaterialTheme.spacing.small))
            }

            Button(
                onClick = { candidate?.let(onSave) },
                enabled = candidate != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MaterialTheme.spacing.small),
            ) {
                Text("Save recipe")
            }
        }
    }
}

@Composable
private fun IngredientEditor(
    state: IngredientEditState,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.small),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                // Live feedback: the icon shows which catalogue concept the name resolved to.
                Image(
                    painter = painterResource(
                        resolveIconRes(state.resolvedConceptId(), null, state.name),
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { state.name = it },
                    label = { Text("Ingredient") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Close, contentDescription = "Remove ingredient")
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                OutlinedTextField(
                    value = state.amount,
                    onValueChange = { state.amount = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    // Weights rather than fixed widths: at large font scales the labels clipped.
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.unit,
                    onValueChange = { state.unit = it },
                    label = { Text("Unit") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() },
                    ),
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    selected = state.optional,
                    onClick = { state.optional = !state.optional },
                    label = { Text("Optional") },
                )
            }
        }
    }
}
