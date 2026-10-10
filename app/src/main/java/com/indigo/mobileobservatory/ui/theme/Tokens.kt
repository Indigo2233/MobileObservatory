package com.indigo.mobileobservatory.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Night Cockpit design tokens.
 *
 * The observatory runs outdoors in the dark: every surface is deep, every
 * status colour carries a companion icon/text, and every interactive target is
 * at least [ObservatoryDimens.touchTarget] so gloves and cold fingers can still
 * hit it. Red night vision mode maps the same tokens into the red band instead
 * of shipping a second UI.
 */

/** Semantic status colours; always paired with an icon or a label in the UI. */
@Immutable
data class ObservatoryStatusColors(
    /** Connected / ready / idle-ok. */
    val ready: Color,
    /** Busy: slewing, cooling ramp, recording, guiding. */
    val active: Color,
    /** Failure, abort, destructive. */
    val danger: Color,
    /** Guiding / plate solving / auxiliary automation. */
    val guide: Color,
    /** Off, disconnected, unknown. */
    val neutral: Color,
)

@Immutable
data class ObservatoryColors(
    val status: ObservatoryStatusColors,
    /** Panel and bar background, one step above the window background. */
    val panel: Color,
    /** Card background on top of [panel]. */
    val card: Color,
    /** Hairline dividers and outlines. */
    val hairline: Color,
    /** Solid scrim for on-image chips (preview overlays). */
    val scrim: Color,
    /** Text drawn on top of preview imagery. */
    val onImage: Color,
    /** Accent used for numeric readouts (exposure, RA/Dec, temperature). */
    val readout: Color,
)

@Immutable
data class ObservatoryDimens(
    val touchTarget: Dp = 48.dp,
    val controlHeight: Dp = 44.dp,
    val primaryButton: Dp = 56.dp,
    val statusBarHeight: Dp = 40.dp,
    val navBarHeight: Dp = 56.dp,
    val railWidthCompact: Dp = 64.dp,
    val railWidth: Dp = 96.dp,
    val radiusSmall: Dp = 8.dp,
    val radiusMedium: Dp = 12.dp,
    val radiusLarge: Dp = 16.dp,
    val gapXs: Dp = 4.dp,
    val gapS: Dp = 8.dp,
    val gapM: Dp = 12.dp,
    val gapL: Dp = 16.dp,
    val gapXl: Dp = 24.dp,
    /** Minimum gap between two destructive or stepper controls. */
    val dangerGap: Dp = 12.dp,
)

private val DeepSpaceStatus = ObservatoryStatusColors(
    ready = Color(0xFF37C8C3),
    active = Color(0xFFE8A33D),
    danger = Color(0xFFE5544B),
    guide = Color(0xFF9B8CFF),
    neutral = Color(0xFF7A8595),
)

private val RedNightStatus = ObservatoryStatusColors(
    ready = Color(0xFFE5655A),
    active = Color(0xFFB8423A),
    danger = Color(0xFFFF3B30),
    guide = Color(0xFFC74A40),
    neutral = Color(0xFF8A3A34),
)

private val DeepSpaceColors = ObservatoryColors(
    status = DeepSpaceStatus,
    panel = Color(0xFF11151C),
    card = Color(0xFF1A2029),
    hairline = Color(0xFF2A313C),
    scrim = Color(0x99000000),
    onImage = Color(0xFFD9DEE7),
    readout = Color(0xFFB9C7FF),
)

private val RedNightColors = ObservatoryColors(
    status = RedNightStatus,
    panel = Color(0xFF120202),
    card = Color(0xFF1C0505),
    hairline = Color(0xFF3A0A08),
    scrim = Color(0xAA1A0000),
    onImage = Color(0xFFDE6255),
    readout = Color(0xFFFF8A7D),
)

object ObservatoryTokens {
    fun colors(redNight: Boolean): ObservatoryColors =
        if (redNight) RedNightColors else DeepSpaceColors

    val dimens: ObservatoryDimens = ObservatoryDimens()
}

val LocalObservatoryColors = staticCompositionLocalOf { DeepSpaceColors }
val LocalObservatoryDimens = staticCompositionLocalOf { ObservatoryDimens() }
val LocalRedNightMode = staticCompositionLocalOf { false }
