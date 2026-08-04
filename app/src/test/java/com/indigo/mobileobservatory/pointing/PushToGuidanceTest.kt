package com.indigo.mobileobservatory.pointing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushToGuidanceTest {
    @Test
    fun azimuthWrapsAcrossZero() {
        val d = PushToGuidance.shortestAzimuthDelta(350.0, 10.0)
        assertEquals(20.0, d, 1e-9)
        val d2 = PushToGuidance.shortestAzimuthDelta(10.0, 350.0)
        assertEquals(-20.0, d2, 1e-9)
    }

    @Test
    fun farMediumNearOnTargetBands() {
        val far = PushToGuidance.compute(30.0, 100.0, 30.0, 130.0, eyepieceFovDeg = 1.5)
        assertEquals(GuidanceProximity.FAR, far.proximity)
        assertEquals(30.0, far.deltaAzDeg, 1e-6)

        val med = PushToGuidance.compute(40.0, 50.0, 43.0, 52.0, eyepieceFovDeg = 1.5)
        assertEquals(GuidanceProximity.MEDIUM, med.proximity)

        val near = PushToGuidance.compute(40.0, 50.0, 40.4, 50.3, eyepieceFovDeg = 1.5)
        assertEquals(GuidanceProximity.NEAR, near.proximity)

        val on = PushToGuidance.compute(40.0, 50.0, 40.1, 50.1, eyepieceFovDeg = 1.5)
        assertEquals(GuidanceProximity.ON_TARGET, on.proximity)
    }

    @Test
    fun onTargetHysteresisPreventsFlicker() {
        val fov = 1.5
        val leave = fov * 0.45
        val stillOn = PushToGuidance.classifyProximity(
            separationDeg = leave * 0.9,
            eyepieceFovDeg = fov,
            previous = GuidanceProximity.ON_TARGET
        )
        assertEquals(GuidanceProximity.ON_TARGET, stillOn)

        val left = PushToGuidance.classifyProximity(
            separationDeg = leave * 1.1,
            eyepieceFovDeg = fov,
            previous = GuidanceProximity.ON_TARGET
        )
        assertTrue(left != GuidanceProximity.ON_TARGET)
    }

    @Test
    fun zenithDegenerateFlag() {
        val cmd = PushToGuidance.compute(85.0, 10.0, 86.0, 100.0)
        assertTrue(cmd.zenithDegenerate)
        val low = PushToGuidance.compute(40.0, 10.0, 45.0, 20.0)
        assertFalse(low.zenithDegenerate)
    }

    @Test
    fun eyepieceFovFraction() {
        val cmd = PushToGuidance.compute(30.0, 0.0, 33.0, 0.0, eyepieceFovDeg = 1.5)
        assertEquals(3.0 / 1.5, cmd.eyepieceFovFraction, 1e-6)
    }
}
