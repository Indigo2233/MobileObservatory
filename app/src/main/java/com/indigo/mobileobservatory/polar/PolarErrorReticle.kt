package com.indigo.mobileobservatory.polar

import kotlin.math.*

/** Knob-coordinate error diagram in arcminutes, independent of camera rotation and image scale.
 * Matches the calculator's hypot(azimuth, altitude) convention, not star displacement in the WCS.
 * Positive x is east; positive y is up. Moving the current point toward zero corrects the axis.
 */
data class PolarErrorReticle(val eastArcMin: Double, val upArcMin: Double, val stepArcMin: Double) {
    val rings get() = (1..3).map { it * stepArcMin }
    val radiusArcMin get() = 3 * stepArcMin

    companion object {
        fun from(result: PolarAlignmentResult, latitudeDeg: Double): PolarErrorReticle {
            val east = abs(result.azimuthErrorArcMin) * if (polarAdjustWest(result.azimuthErrorDeg, latitudeDeg)) 1 else -1
            val up = abs(result.altitudeErrorArcMin) * if (polarAdjustDown(result.altitudeErrorDeg, latitudeDeg)) 1 else -1
            // Leave room for the current marker; retain useful sub-arcminute rings at zero error.
            val minimumStep = max(.5, hypot(east, up) * 1.15 / 3)
            val decade = 10.0.pow(floor(log10(minimumStep)))
            val step = listOf(1.0, 2.0, 5.0, 10.0).first { it * decade >= minimumStep } * decade
            return PolarErrorReticle(east, up, step)
        }
    }
}
