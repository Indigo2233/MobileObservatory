package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class PolarMountRotationTest {
    @Test fun measuredAxisReachesThePoleWhenBothResidualsAreZero() {
        for (latitude in listOf(30.0, -33.0, 65.0, -60.0))
            for (azimuth in listOf(-12.0, -.4, .4, 12.0))
                for (altitude in listOf(-3.0, .3, 3.0)) {
                    val initial = axis(latitude, azimuth, altitude)
                    val corrected = PolarMountRotation.forResidual(initial, azimuth, altitude, 0.0, 0.0)
                    assertVector(axis(latitude, 0.0, 0.0), corrected)
                }
    }

    @Test fun eachPartialCorrectionProducesItsDeclaredMountAxis() {
        for (latitude in listOf(30.0, -33.0))
            for (azimuth in listOf(-5.0, 4.0))
                for (altitude in listOf(-2.0, 1.5)) {
                    val initial = axis(latitude, azimuth, altitude)
                    for ((remainingAzimuth, remainingAltitude) in listOf(
                        azimuth to altitude, 0.0 to altitude, azimuth to 0.0, .2 to -.1)) {
                        assertVector(axis(latitude, remainingAzimuth, remainingAltitude),
                            PolarMountRotation.forResidual(initial, azimuth, altitude,
                                remainingAzimuth, remainingAltitude))
                    }
                }
    }

    @Test fun inverseRestoresAnArbitraryImageRay() {
        val ray = PolarVector3.fromTopocentric(TopocentricCoordinates(55.0, 35.0))
        val moved = PolarMountRotation.forResidual(ray, 4.0, 2.0, .3, -.2)
        assertVector(ray, PolarMountRotation.inverseForResidual(moved, 4.0, 2.0, .3, -.2))
    }

    private fun axis(latitude: Double, azimuth: Double, altitude: Double) =
        PolarVector3.fromTopocentric(TopocentricCoordinates(
            abs(latitude) + if (latitude > 0) altitude else -altitude,
            (if (latitude > 0) 0.0 else 180.0) + azimuth))

    private fun assertVector(expected: PolarVector3, actual: PolarVector3) {
        assertEquals(expected.x, actual.x, 1e-12)
        assertEquals(expected.y, actual.y, 1e-12)
        assertEquals(expected.z, actual.z, 1e-12)
    }
}
