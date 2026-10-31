package com.indigo.mobileobservatory.polar

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class PolarErrorReticleTest {
    private fun result(az: Double, alt: Double) = PolarAlignmentResult(0.0, 30.0, alt / 60, az / 60, hypot(az, alt) / 60)

    @Test fun ringUnitsAndPointDistanceMatchReportedResidual() {
        val r = PolarErrorReticle.from(result(3.0, 4.0), 30.0)
        assertEquals(listOf(2.0, 4.0, 6.0), r.rings)
        assertEquals(5.0, hypot(r.eastArcMin, r.upArcMin), 1e-10)
        assertEquals(3.0, r.eastArcMin, 1e-10)
        assertEquals(4.0, r.upArcMin, 1e-10)
    }

    @Test fun southernHemisphereReversesPhysicalDirections() {
        for (az in listOf(-3.0, 3.0)) for (alt in listOf(-4.0, 4.0)) {
            val north = PolarErrorReticle.from(result(az, alt), 30.0)
            val south = PolarErrorReticle.from(result(az, alt), -30.0)
            assertEquals(-north.eastArcMin, south.eastArcMin, 1e-10)
            assertEquals(-north.upArcMin, south.upArcMin, 1e-10)
            assertEquals(north.rings, south.rings)
        }
    }

    @Test fun retainedScaleDoesNotZoomInAsTheResidualShrinks() {
        val initial = PolarErrorReticle.from(result(12.0, 8.0), 30.0)
        val updated = PolarErrorReticle.from(result(1.0, .5), 30.0, initial.stepArcMin)
        assertEquals(initial.rings, updated.rings)
    }

    @Test fun scaleRemainsFiniteAtZeroAndContainsLargeErrors() {
        val zero = PolarErrorReticle.from(result(0.0, 0.0), 30.0)
        assertEquals(listOf(.5, 1.0, 1.5), zero.rings)
        for (error in listOf(.001, .5, 1.5, 6.0, 30.0, 60.0, 600.0)) {
            val r = PolarErrorReticle.from(result(error, error), 30.0)
            assertTrue(r.radiusArcMin > hypot(r.eastArcMin, r.upArcMin))
        }
    }
}
