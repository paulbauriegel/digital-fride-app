package com.example.groceriesapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout spacing based on an 8dp grid. [extraSmall] is the supported half-step for compact details.
 */
@Immutable
data class AppSpacing(
    val none: Dp = 0.dp,
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp,
    val twoExtraLarge: Dp = 40.dp,
    val threeExtraLarge: Dp = 48.dp,
)

internal val LocalAppSpacing = staticCompositionLocalOf { AppSpacing() }

val MaterialTheme.spacing: AppSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalAppSpacing.current
