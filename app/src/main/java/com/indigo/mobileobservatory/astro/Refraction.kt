package com.indigo.mobileobservatory.astro

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tan

/**
 * Atmospheric conditions used for the refraction model. Defaults are the standard atmosphere that
 * NINA falls back to whenever no weather station is connected.
 */
data class RefractionParameters(
    val pressureHPa: Double = 1013.25,
    val temperatureC: Double = 15.0,
    /** Relative humidity as a fraction in [0, 1]. */
    val relativeHumidity: Double = 0.0,
    val wavelengthMicron: Double = 0.55
) {
    companion object {
        val STANDARD = RefractionParameters()
    }
}

/** The two constants of the `A tan z + B tan^3 z` refraction model, in radians. */
data class RefractionConstants(val a: Double, val b: Double)

/**
 * Port of SOFA's `iauRefco` plus the zenith-distance model it feeds.
 *
 * Refraction is only applied to altitudes above a few degrees during polar alignment, so the simple
 * two-term Green expansion is more than sufficient and avoids the near-horizon divergence.
 */
object Refraction {
    private const val MIN_REFRACTED_ALTITUDE_DEG = 1.0

    fun constants(parameters: RefractionParameters): RefractionConstants {
        val optical = parameters.wavelengthMicron <= 100.0
        val t = parameters.temperatureC.coerceIn(-150.0, 200.0)
        val p = parameters.pressureHPa.coerceIn(0.0, 10_000.0)
        val r = parameters.relativeHumidity.coerceIn(0.0, 1.0)
        val w = parameters.wavelengthMicron.coerceIn(0.1, 1.0e6)

        val waterVapourPressure = if (p > 0.0) {
            val saturation = 10.0.pow((0.7859 + 0.03477 * t) / (1.0 + 0.00412 * t)) *
                (1.0 + p * (4.5e-6 + 6.0e-10 * t * t))
            r * saturation / (1.0 - (1.0 - r) * saturation / p)
        } else {
            0.0
        }

        val tk = t + 273.15
        val gamma = if (optical) {
            val wlsq = w * w
            ((77.53484e-6 + (4.39108e-7 + 3.666e-9 / wlsq) / wlsq) * p - 11.2684e-6 * waterVapourPressure) / tk
        } else {
            (77.6890e-6 * p - (6.3938e-6 - 0.375463 / tk) * waterVapourPressure) / tk
        }

        var beta = 4.4474e-6 * tk
        if (!optical) beta -= 0.0074 * waterVapourPressure * beta

        return RefractionConstants(
            a = gamma * (1.0 - beta),
            b = -gamma * (beta - gamma / 2.0)
        )
    }

    /**
     * Refraction in degrees for a given observed (refracted) altitude. Green's expansion is defined
     * in terms of the observed zenith distance, so this is the direct evaluation.
     */
    fun refractionAtObservedAltitudeDeg(
        observedAltitudeDeg: Double,
        constants: RefractionConstants
    ): Double {
        if (observedAltitudeDeg < MIN_REFRACTED_ALTITUDE_DEG) return 0.0
        val tanZ = tan((90.0 - observedAltitudeDeg).toRad())
        return (constants.a * tanZ + constants.b * tanZ * tanZ * tanZ).toDeg()
    }

    /** Apparent altitude of an object whose geometric altitude is [trueAltitudeDeg]. */
    fun refractedAltitudeDeg(trueAltitudeDeg: Double, parameters: RefractionParameters): Double =
        refractedAltitudeDeg(trueAltitudeDeg, constants(parameters))

    fun refractedAltitudeDeg(trueAltitudeDeg: Double, constants: RefractionConstants): Double {
        if (trueAltitudeDeg < MIN_REFRACTED_ALTITUDE_DEG) return trueAltitudeDeg
        // Green's model is expressed against the observed altitude, so invert it by fixed point.
        var observed = trueAltitudeDeg
        repeat(6) {
            val next = trueAltitudeDeg + refractionAtObservedAltitudeDeg(observed, constants)
            if (abs(next - observed) < 1.0e-10) return next
            observed = next
        }
        return observed
    }

    /** Geometric altitude of an object seen at [observedAltitudeDeg]. */
    fun trueAltitudeDeg(observedAltitudeDeg: Double, constants: RefractionConstants): Double =
        observedAltitudeDeg - refractionAtObservedAltitudeDeg(observedAltitudeDeg, constants)
}
