package com.indigo.mobileobservatory.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AstroDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7B8CFF),
    onPrimary = Color(0xFF001A6E),
    primaryContainer = Color(0xFF002FA7),
    onPrimaryContainer = Color(0xFFBDC4FF),
    secondary = Color(0xFFB0C6FF),
    onSecondary = Color(0xFF002D6E),
    secondaryContainer = Color(0xFF00429B),
    onSecondaryContainer = Color(0xFFD9E2FF),
    tertiary = Color(0xFFC3A0FF),
    onTertiary = Color(0xFF3A0093),
    tertiaryContainer = Color(0xFF5200CD),
    onTertiaryContainer = Color(0xFFE4D0FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF141418),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF1E1E24),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F909A),
    outlineVariant = Color(0xFF44464F),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF303034),
    inversePrimary = Color(0xFF4050C8),
)

private val AstroRedColorScheme = darkColorScheme(
    primary = Color(0xFFFF3B30),
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
    background = Color(0xFF030000),
    onBackground = Color(0xFFFF6B60),
    surface = Color(0xFF070000),
    onSurface = Color(0xFFFF6B60),
    surfaceVariant = Color(0xFF120000),
    onSurfaceVariant = Color(0xFFE2584E),
    outline = Color(0xFF8F2A24),
    outlineVariant = Color(0xFF3A0906),
    inverseSurface = Color(0xFFFF6B60),
    inverseOnSurface = Color(0xFF180000),
    inversePrimary = Color(0xFFB00000),
)

@Composable
fun MobileObservatoryTheme(
    redNightMode: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (redNightMode) AstroRedColorScheme else AstroDarkColorScheme,
        typography = Typography(),
        content = content
    )
}
