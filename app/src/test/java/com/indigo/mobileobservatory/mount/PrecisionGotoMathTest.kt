package com.indigo.mobileobservatory.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecisionGotoMathTest {
    @Test
    fun defaultStopIsSixArcminAndUserValuesAreClamped() {
        assertEquals(6.0, PrecisionGotoMath.TOLERANCE_ARCMIN, 0.0)
        assertTrue(PrecisionGotoMath.withinTolerance(5.9))
        assertTrue(PrecisionGotoMath.withinTolerance(6.0))
        assertTrue(!PrecisionGotoMath.withinTolerance(6.1))
        assertEquals(1.0, PrecisionGotoMath.clampToleranceArcmin(0.2), 0.0)
        assertEquals(30.0, PrecisionGotoMath.clampToleranceArcmin(90.0), 0.0)
        assertTrue(PrecisionGotoMath.withinTolerance(8.0, toleranceArcmin = 10.0))
        assertTrue(!PrecisionGotoMath.withinTolerance(8.0, toleranceArcmin = 6.0))
        assertEquals(
            "star_map_precision_tolerance_arcmin",
            PrecisionGotoMath.PREFS_TOLERANCE_ARCMIN
        )
    }

    @Test
    fun angularSeparationMatchesTwoArcminScale() {
        val target = MountCoordinates(raHours = 5.0, decDeg = 10.0)
        // ~2 arcmin east in RA at dec=10: deltaRaDeg ≈ 2/60 / cos(10°)
        val deltaRaHours = (2.0 / 60.0) / 15.0
        val near = MountCoordinates(raHours = 5.0 + deltaRaHours, decDeg = 10.0)
        val errorArcmin = PrecisionGotoMath.degreesToArcmin(
            PrecisionGotoMath.angularSeparationDeg(target, near)
        )
        assertTrue(errorArcmin in 1.8..2.2)
    }

    @Test
    fun correctiveCommandAppliesSkyOffsetOntoMountReported() {
        val mount = MountCoordinates(raHours = 1.0, decDeg = 20.0)
        val target = MountCoordinates(raHours = 2.0, decDeg = 21.0)
        val solved = MountCoordinates(raHours = 1.5, decDeg = 19.5)
        val command = PrecisionGotoMath.correctiveCommand(mount, target, solved)
        assertEquals(1.5, command.raHours, 1e-9)
        assertEquals(21.5, command.decDeg, 1e-9)
    }

    @Test
    fun correctiveCommandWrapsRaAndClampsDec() {
        val mount = MountCoordinates(raHours = 23.5, decDeg = 89.0)
        val target = MountCoordinates(raHours = 0.5, decDeg = 90.0)
        val solved = MountCoordinates(raHours = 23.0, decDeg = 88.0)
        val command = PrecisionGotoMath.correctiveCommand(mount, target, solved)
        assertEquals(1.0, command.raHours, 1e-9)
        assertEquals(90.0, command.decDeg, 1e-9)
    }
}
