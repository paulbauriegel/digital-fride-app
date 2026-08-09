package com.example.groceriesapp.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Groceries' dark-first Material 3 theme.
 *
 * Dynamic color is opt-in so the recognizable green/lime identity remains the default.
 */
@Composable
fun GroceriesTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }

        darkTheme -> GroceriesDarkColorScheme
        else -> GroceriesLightColorScheme
    }
    val scanStatusColors = if (darkTheme) DarkScanStatusColors else LightScanStatusColors
    val storageLocationColors =
        if (darkTheme) DarkStorageLocationColors else LightStorageLocationColors

    CompositionLocalProvider(
        LocalAppSpacing provides AppSpacing(),
        LocalScanStatusColors provides scanStatusColors,
        LocalStorageLocationColors provides storageLocationColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GroceriesTypography,
            shapes = GroceriesShapes,
            content = content,
        )
    }
}
