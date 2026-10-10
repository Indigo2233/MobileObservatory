package com.indigo.mobileobservatory.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val AstroDarkColorScheme = darkColorScheme(
    primary = Color(0xFF8FA3FF),
    onPrimary = Color(0xFF001A6E),
    primaryContainer = Color(0xFF24336E),
    onPrimaryContainer = Color(0xFFCFD8FF),
    secondary = Color(0xFFA9C0FF),
    onSecondary = Color(0xFF002D6E),
    secondaryContainer = Color(0xFF1D3060),
    onSecondaryContainer = Color(0xFFD9E2FF),
    tertiary = Color(0xFFC3A0FF),
    onTertiary = Color(0xFF3A0093),
    tertiaryContainer = Color(0xFF3B1E77),
    onTertiaryContainer = Color(0xFFE4D0FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF11151C),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF1A2029),
    onSurfaceVariant = Color(0xFFA9B2C0),
    outline = Color(0xFF6E7887),
    outlineVariant = Color(0xFF2A313C),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF303034),
    inversePrimary = Color(0xFF4A5BB8),
)

private val AstroRedColorScheme = darkColorScheme(
    primary = Color(0xFFFF5242),
    onPrimary = Color(0xFF220000),
    primaryContainer = Color(0xFF5A0000),
    onPrimaryContainer = Color(0xFFFF9A92),
    secondary = Color(0xFFE06B5F),
    onSecondary = Color(0xFF220000),
    secondaryContainer = Color(0xFF3A0500),
    onSecondaryContainer = Color(0xFFFFB4AA),
    tertiary = Color(0xFFFF6A4A),
    onTertiary = Color(0xFF2A0400),
    tertiaryContainer = Color(0xFF4A0900),
    onTertiaryContainer = Color(0xFFFFB4A4),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF220000),
    errorContainer = Color(0xFF4A0000),
    onErrorContainer = Color(0xFFFFB4AB),
    background = Color(0xFF0A0000),
    onBackground = Color(0xFFE06055),
    surface = Color(0xFF140202),
    onSurface = Color(0xFFE06055),
    surfaceVariant = Color(0xFF1C0505),
    onSurfaceVariant = Color(0xFFC7524A),
    outline = Color(0xFF8F2A24),
    outlineVariant = Color(0xFF3A0A08),
    inverseSurface = Color(0xFFE06055),
    inverseOnSurface = Color(0xFF180000),
    inversePrimary = Color(0xFFB00000),
)

/**
 * Root theme. Keeps the original entry point so `MainActivity` and existing
 * call sites do not change: dark cockpit colours by default, red night vision
 * on request. Design tokens are provided alongside the Material scheme.
 */
@Composable
fun MobileObservatoryTheme(
    redNightMode: Boolean = false,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalObservatoryColors provides ObservatoryTokens.colors(redNightMode),
        LocalObservatoryDimens provides ObservatoryTokens.dimens,
        LocalRedNightMode provides redNightMode
    ) {
        MaterialTheme(
            colorScheme = if (redNightMode) AstroRedColorScheme else AstroDarkColorScheme,
            typography = ObservatoryTypography,
            content = content
        )
    }
}

/** Convenience accessors for the cockpit tokens. */
object ObservatoryTheme {
    val colors
        @Composable get() = LocalObservatoryColors.current

    val dimens
        @Composable get() = LocalObservatoryDimens.current

    val redNight
        @Composable get() = LocalRedNightMode.current
}
