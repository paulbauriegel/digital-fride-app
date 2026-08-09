package com.example.groceriesapp.ui.components.charts

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Ordered categorical palette for donut slices and their legend rows. Both consume the list in
 * the same rank order, so slice N and legend row N always agree. The trailing [Color] is reserved
 * for the "Other" bucket — callers that render top-N + Other should use [chartColors] for the
 * top entries and [otherChartColor] for the remainder.
 */
@Composable
@ReadOnlyComposable
fun chartColors(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.secondary,
    MaterialTheme.colorScheme.inversePrimary,
    MaterialTheme.colorScheme.surfaceTint,
)

@Composable
@ReadOnlyComposable
fun otherChartColor(): Color = MaterialTheme.colorScheme.outline
