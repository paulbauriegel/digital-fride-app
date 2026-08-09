package com.example.groceriesapp.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Query state for [GroceriesSearchBar], bundling the M3 [SearchBarState] (collapsed/expanded
 * animation) with the [TextFieldState] holding the typed text.
 *
 * Screens read [query] to filter, and call [expand] from their top-bar search icon. Collapsing
 * clears the text, matching how the previous toggle-reveal field reset itself on close.
 */
@Stable
@OptIn(ExperimentalMaterial3Api::class)
class GroceriesSearchState internal constructor(
    val barState: SearchBarState,
    val textFieldState: TextFieldState,
) {
    /** The current text, snapshot-observable — reading it in a composable recomposes on edit. */
    val query: String
        get() = textFieldState.text.toString()

    val expanded: Boolean
        get() = barState.targetValue == SearchBarValue.Expanded

    suspend fun expand() = barState.animateToExpanded()

    suspend fun collapse() = barState.animateToCollapsed()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberGroceriesSearchState(): GroceriesSearchState {
    val barState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState()
    return remember(barState, textFieldState) {
        GroceriesSearchState(barState, textFieldState)
    }
}

/**
 * The app's single search affordance: M3's expanding search bar, which animates from the top bar
 * into a full-screen search view holding live results.
 *
 * This replaces three separately hand-rolled `OutlinedTextField` search fields — one of which
 * faked this component's look with a 50%-rounded shape, transparent borders and a manual
 * `surfaceContainerHigh` fill. A text field is an Input-category component; search is its own
 * Navigation-category component, and using it gets the shape, tonal colors, motion and
 * predictive-back handling from the framework instead of by hand.
 *
 * Renders nothing while collapsed — screens keep their own `TopAppBar` and call
 * [GroceriesSearchState.expand] from its search icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceriesSearchBar(
    state: GroceriesSearchState,
    placeholder: String,
    modifier: Modifier = Modifier,
    results: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Collapsing (back gesture, back arrow, result tap) leaves no stale query behind.
    LaunchedEffect(state.barState.targetValue) {
        if (state.barState.targetValue == SearchBarValue.Collapsed) {
            state.textFieldState.clearText()
        }
    }

    ExpandedFullScreenSearchBar(
        state = state.barState,
        inputField = {
            SearchBarDefaults.InputField(
                textFieldState = state.textFieldState,
                searchBarState = state.barState,
                // Filtering is live, so submitting only dismisses the keyboard; the framework
                // handles that. Nothing further to do here.
                onSearch = {},
                placeholder = { Text(placeholder) },
                leadingIcon = {
                    IconButton(
                        onClick = { scope.launch { state.barState.animateToCollapsed() } },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close search",
                        )
                    }
                },
                trailingIcon = {
                    if (state.textFieldState.text.isNotEmpty()) {
                        IconButton(onClick = { state.textFieldState.clearText() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
            )
        },
        modifier = modifier,
        content = results,
    )
}
