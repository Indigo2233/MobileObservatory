package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.mount.MountDirection.*
import org.junit.Assert.*
import org.junit.Test

class GuideBacklashCompensationTest {
    @Test fun onlyAnAcceptedReversalTakesUpBacklashOnce() {
        val compensation = GuideBacklashCompensation()
        compensation.accepted(SOUTH)
        assertEquals(1600, compensation.pulse(100, NORTH, 1500, true))
        // A rejected command has not changed the physical direction.
        assertEquals(1600, compensation.pulse(100, NORTH, 1500, true))
        compensation.accepted(NORTH)
        assertEquals(100, compensation.pulse(100, NORTH, 1500, true))
        assertEquals(1600, compensation.pulse(100, SOUTH, 1500, true))
    }
    @Test fun deadbandDisabledCompensationAndUnknownDirectionNeverAddMotion() {
        val compensation = GuideBacklashCompensation()
        assertEquals(100, compensation.pulse(100, NORTH, 1500, true))
        compensation.accepted(SOUTH)
        assertEquals(0, compensation.pulse(0, NORTH, 1500, true))
        assertEquals(100, compensation.pulse(100, NORTH, 1500, false))
        compensation.reset()
        assertEquals(100, compensation.pulse(100, NORTH, 1500, true))
    }
    @Test fun noisyOrVeryLargeBacklashEstimatesRespectThePulseLimit() {
        val compensation = GuideBacklashCompensation(); compensation.accepted(SOUTH)
        assertEquals(4000, compensation.pulse(2000, NORTH, Int.MAX_VALUE, true))
        assertEquals(100, compensation.pulse(100, NORTH, -100, true))
    }
}
