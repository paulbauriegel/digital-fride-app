package com.example.groceriesapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colors for receipt-capture feedback.
 *
 * Each foreground color is paired with an `on*` role, while the softer container pair is intended
 * for guidance banners, progress tracks, and highlighted scan regions.
 */
@Immutable
data class ScanStatusColors(
    val good: Color,
    val onGood: Color,
    val goodContainer: Color,
    val onGoodContainer: Color,
    val caution: Color,
    val onCaution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val lost: Color,
    val onLost: Color,
    val lostContainer: Color,
    val onLostContainer: Color,
)

internal val DarkScanStatusColors = ScanStatusColors(
    good = Color(0xFFA6D98A),
    onGood = Color(0xFF14390A),
    goodContainer = Color(0xFF284E1B),
    onGoodContainer = Color(0xFFC1F5A6),
    caution = Color(0xFFF0C75E),
    onCaution = Color(0xFF3D2F00),
    cautionContainer = Color(0xFF574500),
    onCautionContainer = Color(0xFFFFE38B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    lost = Color(0xFFFFB68C),
    onLost = Color(0xFF552000),
    lostContainer = Color(0xFF783100),
    onLostContainer = Color(0xFFFFDBCA),
)

internal val LightScanStatusColors = ScanStatusColors(
    good = Color(0xFF2F6B1B),
    onGood = Color(0xFFFFFFFF),
    goodContainer = Color(0xFFB9F397),
    onGoodContainer = Color(0xFF082100),
    caution = Color(0xFF765A00),
    onCaution = Color(0xFFFFFFFF),
    cautionContainer = Color(0xFFFFE082),
    onCautionContainer = Color(0xFF241A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    lost = Color(0xFF9A4610),
    onLost = Color(0xFFFFFFFF),
    lostContainer = Color(0xFFFFDBCA),
    onLostContainer = Color(0xFF351000),
)

internal val LocalScanStatusColors = staticCompositionLocalOf { DarkScanStatusColors }

val MaterialTheme.scanStatusColors: ScanStatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalScanStatusColors.current
