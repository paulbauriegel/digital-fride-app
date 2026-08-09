package com.example.groceriesapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.groceriesapp.data.taxonomy.StorageLocation

/**
 * Where-it-lives colour language: fridge reads green, freezer blue, pantry amber.
 *
 * The designs colour-code storage everywhere it appears — summary cards, row badges, filter pills —
 * but the scheme itself has no blue at all (`tertiary` is gold), so these can't be expressed with
 * the standard roles. Modelled on [ScanStatusColors]: a container plus its `on` pair, resolved per
 * theme, so callers never pick a foreground themselves.
 */
@Immutable
data class StorageLocationColors(
    val fridgeContainer: Color,
    val onFridgeContainer: Color,
    val freezerContainer: Color,
    val onFreezerContainer: Color,
    val pantryContainer: Color,
    val onPantryContainer: Color,
) {
    fun containerFor(location: StorageLocation): Color = when (location) {
        StorageLocation.FRIDGE -> fridgeContainer
        StorageLocation.FREEZER -> freezerContainer
        StorageLocation.PANTRY -> pantryContainer
    }

    fun onContainerFor(location: StorageLocation): Color = when (location) {
        StorageLocation.FRIDGE -> onFridgeContainer
        StorageLocation.FREEZER -> onFreezerContainer
        StorageLocation.PANTRY -> onPantryContainer
    }
}

internal val LightStorageLocationColors = StorageLocationColors(
    fridgeContainer = Color(0xFFDCEFDC),
    onFridgeContainer = Color(0xFF0C3D18),
    freezerContainer = Color(0xFFDCEAF6),
    onFreezerContainer = Color(0xFF0C3A56),
    pantryContainer = Color(0xFFFAE6D5),
    onPantryContainer = Color(0xFF4E2A0C),
)

internal val DarkStorageLocationColors = StorageLocationColors(
    fridgeContainer = Color(0xFF1D3A24),
    onFridgeContainer = Color(0xFFBCE7BC),
    freezerContainer = Color(0xFF16323F),
    onFreezerContainer = Color(0xFFB3D8EC),
    pantryContainer = Color(0xFF3F2A19),
    onPantryContainer = Color(0xFFEFCDAA),
)

internal val LocalStorageLocationColors =
    staticCompositionLocalOf { DarkStorageLocationColors }

val MaterialTheme.storageLocationColors: StorageLocationColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStorageLocationColors.current
