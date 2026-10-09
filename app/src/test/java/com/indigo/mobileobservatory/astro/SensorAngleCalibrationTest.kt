package com.indigo.mobileobservatory.astro

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorAngleCalibrationTest {
    @Test
    fun calibrationMapsSkyAndMechanicalAnglesInBothDirections() {
        val direct = SensorAngleCalibration(reversed = false).calibrated(87.0, 12.0)
        assertEquals(75.0, direct.offsetDeg, 1e-9)
        assertEquals(87.0, direct.skyPositionAngle(12.0), 1e-9)
        assertEquals(12.0, direct.mechanicalAngle(87.0), 1e-9)

        val reversed = SensorAngleCalibration(reversed = true).calibrated(7.0, 12.0)
        assertEquals(19.0, reversed.offsetDeg, 1e-9)
        assertEquals(7.0, reversed.skyPositionAngle(12.0), 1e-9)
        assertEquals(12.0, reversed.mechanicalAngle(7.0), 1e-9)
    }

    @Test
    fun angularErrorUsesShortestDistanceAcrossZero() {
        assertEquals(2.0, SensorAngleCalibration.angularError(359.0, 1.0), 1e-9)
    }
}
