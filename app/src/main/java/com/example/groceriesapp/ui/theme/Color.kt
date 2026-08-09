package com.example.groceriesapp.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The accent palettes stay green/lime — that is the app's identity. The neutral and
// neutral-variant stops are near-grey rather than green-tinted, matching the designs, so surfaces
// read as a calm background and the green reads as accent instead of tinting the whole screen.

private val PrimaryFixed = Color(0xFFB9F397)
private val PrimaryFixedDim = Color(0xFF9ED77D)
private val OnPrimaryFixed = Color(0xFF082100)
private val OnPrimaryFixedVariant = Color(0xFF23510F)

private val SecondaryFixed = Color(0xFFD5E8CB)
private val SecondaryFixedDim = Color(0xFFB9CCAF)
private val OnSecondaryFixed = Color(0xFF111F0D)
private val OnSecondaryFixedVariant = Color(0xFF3B4B35)

private val TertiaryFixed = Color(0xFFF4E387)
private val TertiaryFixedDim = Color(0xFFD7C76E)
private val OnTertiaryFixed = Color(0xFF211B00)
private val OnTertiaryFixedVariant = Color(0xFF504700)

internal val GroceriesDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA6D98A),
    onPrimary = Color(0xFF14390A),
    primaryContainer = Color(0xFF284E1B),
    onPrimaryContainer = Color(0xFFC1F5A6),
    inversePrimary = Color(0xFF386A20),
    secondary = Color(0xFFB9CCAF),
    onSecondary = Color(0xFF253420),
    secondaryContainer = Color(0xFF3B4B35),
    onSecondaryContainer = Color(0xFFD5E8CB),
    tertiary = Color(0xFFD7C76E),
    onTertiary = Color(0xFF373000),
    tertiaryContainer = Color(0xFF504700),
    onTertiaryContainer = Color(0xFFF4E387),
    background = Color(0xFF101110),
    onBackground = Color(0xFFE3E3E0),
    surface = Color(0xFF101110),
    onSurface = Color(0xFFE3E3E0),
    surfaceVariant = Color(0xFF43483F),
    onSurfaceVariant = Color(0xFFC3C8BE),
    surfaceTint = Color(0xFFA6D98A),
    inverseSurface = Color(0xFFE3E3E0),
    inverseOnSurface = Color(0xFF2E302D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8D9188),
    outlineVariant = Color(0xFF43483F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF363836),
    surfaceContainer = Color(0xFF1C1D1C),
    surfaceContainerHigh = Color(0xFF262726),
    surfaceContainerHighest = Color(0xFF313231),
    surfaceContainerLow = Color(0xFF181918),
    surfaceContainerLowest = Color(0xFF0B0B0B),
    surfaceDim = Color(0xFF101110),
    primaryFixed = PrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixed = OnPrimaryFixed,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixed = OnSecondaryFixed,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixed = OnTertiaryFixed,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)

internal val GroceriesLightColorScheme = lightColorScheme(
    primary = Color(0xFF386A20),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9F397),
    onPrimaryContainer = Color(0xFF082100),
    inversePrimary = Color(0xFFA6D98A),
    secondary = Color(0xFF52634B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8CB),
    onSecondaryContainer = Color(0xFF111F0D),
    tertiary = Color(0xFF6B5E16),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF4E387),
    onTertiaryContainer = Color(0xFF211B00),
    background = Color(0xFFF9F9F7),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFF9F9F7),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFE1E3DE),
    onSurfaceVariant = Color(0xFF43483F),
    surfaceTint = Color(0xFF386A20),
    inverseSurface = Color(0xFF2E302D),
    inverseOnSurface = Color(0xFFF0F1EE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF73796F),
    outlineVariant = Color(0xFFC3C8BE),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9F7),
    surfaceContainer = Color(0xFFEEEEEC),
    surfaceContainerHigh = Color(0xFFE8E8E6),
    surfaceContainerHighest = Color(0xFFE2E2E0),
    surfaceContainerLow = Color(0xFFF4F4F2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDADAD8),
    primaryFixed = PrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixed = OnPrimaryFixed,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixed = OnSecondaryFixed,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixed = OnTertiaryFixed,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)
