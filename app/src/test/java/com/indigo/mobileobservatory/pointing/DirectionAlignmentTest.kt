package com.indigo.mobileobservatory.pointing

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionAlignmentTest {
    @Test
    fun plateSolveAlignmentRotatesSubsequentPhoneDirections() {
        val alignment = DirectionAlignment()
        alignment.calibrate(
            Direction3.fromAltAz(20.0, 80.0),
            Direction3.fromAltAz(45.0, 140.0)
        )

        val (altitude, azimuth) = alignment.apply(Direction3.fromAltAz(20.0, 80.0)).toAltAz()

        assertEquals(45.0, altitude, 1e-8)
        assertEquals(140.0, azimuth, 1e-8)
    }

    @Test
    fun identityAlignmentLeavesDirectionUnchanged() {
        val alignment = DirectionAlignment()
        val (altitude, azimuth) = alignment.apply(Direction3.fromAltAz(-12.0, 359.0)).toAltAz()

        assertEquals(-12.0, altitude, 1e-8)
        assertEquals(359.0, azimuth, 1e-8)
    }
}
