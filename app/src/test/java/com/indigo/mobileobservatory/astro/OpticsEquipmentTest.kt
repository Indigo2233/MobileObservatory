package com.indigo.mobileobservatory.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpticsEquipmentTest {
    @Test
    fun eyepieceFovMatchesStellariumRule() {
        // 500 mm scope + 25 mm / 50° ep → 2.5° true FOV, 20×
        val fov = OpticsEquipment.eyepieceTrueFovDeg(500.0, 25.0, 50.0)!!
        val mag = OpticsEquipment.magnification(500.0, 25.0)!!
        assertEquals(2.5, fov, 1e-9)
        assertEquals(20.0, mag, 1e-9)
    }

    @Test
    fun computeEyepieceBundlesOverlay() {
        val ep = EyepieceSpec("t", "t", 10.0, 60.0)
        val c = OpticsEquipment.computeEyepiece(1000.0, ep)
        assertEquals(0.6, c.circleDeg!!, 1e-9)
        assertEquals(100.0, c.magnification!!, 1e-9)
    }

    @Test
    fun computeSensorUsesPlateScale() {
        val sensor = SensorSpec("t", "t", 3.75, 1920, 1080)
        val c = OpticsEquipment.computeSensor(500.0, sensor)
        assertEquals(0.825, c.rectWidthDeg!!, 0.002)
        assertEquals(0.464, c.rectHeightDeg!!, 0.002)
    }

    @Test
    fun rejectsInvalidOptics() {
        assertNull(OpticsEquipment.eyepieceTrueFovDeg(0.0, 25.0, 50.0))
        assertNull(OpticsEquipment.magnification(500.0, 0.0))
        assertNull(OpticsEquipment.connectedSensor(null, 100, 100, "x"))
    }
}
