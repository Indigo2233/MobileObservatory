package com.indigo.mobileobservatory.astro

import kotlin.math.cos
import kotlin.math.sin

data class NutationAngles(val deltaPsiRad: Double, val deltaEpsilonRad: Double)

/**
 * IAU 1980 nutation series truncated to the terms larger than 0.0005 arcseconds.
 *
 * The residual against the full IAU 2000A model stays below about 0.02 arcseconds, which is two
 * orders of magnitude finer than the arcminute-level precision a polar alignment routine reports.
 */
object Nutation {
    // Multipliers of the fundamental arguments D, M, M', F and Omega, five per term.
    private val ARGUMENTS = intArrayOf(
        0, 0, 0, 0, 1,
        -2, 0, 0, 2, 2,
        0, 0, 0, 2, 2,
        0, 0, 0, 0, 2,
        0, 1, 0, 0, 0,
        0, 0, 1, 0, 0,
        -2, 1, 0, 2, 2,
        0, 0, 0, 2, 1,
        0, 0, 1, 2, 2,
        -2, -1, 0, 2, 2,
        -2, 0, 1, 0, 0,
        -2, 0, 0, 2, 1,
        0, 0, -1, 2, 2,
        2, 0, 0, 0, 0,
        0, 0, 1, 0, 1,
        2, 0, -1, 2, 2,
        0, 0, -1, 0, 1,
        0, 0, 1, 2, 1,
        -2, 0, 2, 0, 0,
        0, 0, -2, 2, 1,
        2, 0, 0, 2, 2,
        0, 0, 2, 2, 2,
        0, 0, 2, 0, 0,
        -2, 0, 1, 2, 2,
        0, 0, 0, 2, 0,
        -2, 0, 0, 2, 0,
        0, 0, -1, 2, 1,
        0, 2, 0, 0, 0,
        2, 0, -1, 0, 1,
        -2, 2, 0, 2, 2,
        0, 1, 0, 0, 1,
        -2, 0, 1, 0, 1,
        0, -1, 0, 0, 1,
        0, 0, 2, -2, 0,
        2, 0, -1, 2, 1,
        2, 0, 1, 2, 2,
        0, 1, 0, 2, 2,
        -2, 1, 1, 0, 0,
        0, -1, 0, 2, 2,
        2, 0, 0, 2, 1,
        2, 0, 1, 0, 0,
        -2, 0, 2, 2, 2,
        -2, 0, 1, 2, 1,
        2, 0, -2, 0, 1,
        2, 0, 0, 0, 1,
        0, -1, 1, 0, 0,
        -2, -1, 0, 2, 1,
        -2, 0, 0, 0, 1,
        0, 0, 2, 2, 1
    )

    // Sine coefficient, its rate, cosine coefficient and its rate, all in units of 0.0001".
    private val COEFFICIENTS = doubleArrayOf(
        -171996.0, -174.2, 92025.0, 8.9,
        -13187.0, -1.6, 5736.0, -3.1,
        -2274.0, -0.2, 977.0, -0.5,
        2062.0, 0.2, -895.0, 0.5,
        1426.0, -3.4, 54.0, -0.1,
        712.0, 0.1, -7.0, 0.0,
        -517.0, 1.2, 224.0, -0.6,
        -386.0, -0.4, 200.0, 0.0,
        -301.0, 0.0, 129.0, -0.1,
        217.0, -0.5, -95.0, 0.3,
        -158.0, 0.0, 0.0, 0.0,
        129.0, 0.1, -70.0, 0.0,
        123.0, 0.0, -53.0, 0.0,
        63.0, 0.0, 0.0, 0.0,
        63.0, 0.1, -33.0, 0.0,
        -59.0, 0.0, 26.0, 0.0,
        -58.0, -0.1, 32.0, 0.0,
        -51.0, 0.0, 27.0, 0.0,
        48.0, 0.0, 0.0, 0.0,
        46.0, 0.0, -24.0, 0.0,
        -38.0, 0.0, 16.0, 0.0,
        -31.0, 0.0, 13.0, 0.0,
        29.0, 0.0, 0.0, 0.0,
        29.0, 0.0, -12.0, 0.0,
        26.0, 0.0, 0.0, 0.0,
        -22.0, 0.0, 0.0, 0.0,
        21.0, 0.0, -10.0, 0.0,
        17.0, -0.1, 0.0, 0.0,
        16.0, 0.0, -8.0, 0.0,
        -16.0, 0.1, 7.0, 0.0,
        -15.0, 0.0, 9.0, 0.0,
        -13.0, 0.0, 7.0, 0.0,
        -12.0, 0.0, 6.0, 0.0,
        11.0, 0.0, 0.0, 0.0,
        -10.0, 0.0, 5.0, 0.0,
        -8.0, 0.0, 3.0, 0.0,
        7.0, 0.0, -3.0, 0.0,
        -7.0, 0.0, 0.0, 0.0,
        -7.0, 0.0, 3.0, 0.0,
        -7.0, 0.0, 3.0, 0.0,
        6.0, 0.0, 0.0, 0.0,
        6.0, 0.0, -3.0, 0.0,
        6.0, 0.0, -3.0, 0.0,
        -6.0, 0.0, 3.0, 0.0,
        -6.0, 0.0, 3.0, 0.0,
        5.0, 0.0, 0.0, 0.0,
        -5.0, 0.0, 3.0, 0.0,
        -5.0, 0.0, 3.0, 0.0,
        -5.0, 0.0, 3.0, 0.0
    )

    fun moonAscendingNodeRad(t: Double): Double =
        normalizeRadians(
            (125.04452 + t * (-1934.136261 + t * (0.0020708 + t / 450000.0))).toRad()
        )

    fun compute(t: Double): NutationAngles {
        val d = (297.85036 + t * (445267.111480 + t * (-0.0019142 + t / 189474.0))).toRad()
        val m = (357.52772 + t * (35999.050340 + t * (-0.0001603 - t / 300000.0))).toRad()
        val mPrime = (134.96298 + t * (477198.867398 + t * (0.0086972 + t / 56250.0))).toRad()
        val f = (93.27191 + t * (483202.017538 + t * (-0.0036825 + t / 327270.0))).toRad()
        val omega = moonAscendingNodeRad(t)

        var deltaPsi = 0.0
        var deltaEpsilon = 0.0
        for (term in 0 until ARGUMENTS.size / 5) {
            val a = term * 5
            val argument = ARGUMENTS[a] * d +
                ARGUMENTS[a + 1] * m +
                ARGUMENTS[a + 2] * mPrime +
                ARGUMENTS[a + 3] * f +
                ARGUMENTS[a + 4] * omega

            val c = term * 4
            deltaPsi += (COEFFICIENTS[c] + COEFFICIENTS[c + 1] * t) * sin(argument)
            deltaEpsilon += (COEFFICIENTS[c + 2] + COEFFICIENTS[c + 3] * t) * cos(argument)
        }

        val scale = 1e-4 * ARCSEC_TO_RAD
        return NutationAngles(deltaPsi * scale, deltaEpsilon * scale)
    }
}
