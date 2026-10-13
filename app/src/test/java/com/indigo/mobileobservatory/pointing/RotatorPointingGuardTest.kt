package com.indigo.mobileobservatory.pointing

import org.junit.Assert.assertEquals
import org.junit.Test

class RotatorPointingGuardTest {
    private val idle = RotatorPointingGuard.State(true, false, 20.0, false, 100)

    @Test fun revokesBeforeCommandAndAtBothMotionBoundariesButNotEveryTick() {
        var invalidations = 0
        val guard = RotatorPointingGuard { invalidations++ }
        guard.observe(idle)
        invalidations = 0
        guard.beforeCommand(true)
        assertEquals(1, invalidations)
        guard.observe(idle.copy(moving = true))
        guard.observe(idle.copy(moving = true, angleDeg = 40.0))
        guard.observe(idle.copy(moving = true, angleDeg = 60.0))
        assertEquals(2, invalidations)
        guard.observe(idle.copy(angleDeg = 110.0))
        assertEquals(3, invalidations)
    }

    @Test fun externalMovementCoordinatesAndReconnectRevokeButIdleAndWrappedAnglesDoNot() {
        var invalidations = 0
        val guard = RotatorPointingGuard { invalidations++ }
        guard.observe(idle.copy(connected = false))
        guard.beforeCommand(false)
        assertEquals(0, invalidations)
        guard.observe(idle)
        guard.observe(idle)
        guard.observe(idle.copy(angleDeg = 380.0))
        assertEquals(1, invalidations)
        guard.observe(idle.copy(angleDeg = 21.0))
        guard.observe(idle.copy(angleDeg = 21.0, reversed = true))
        guard.observe(idle.copy(angleDeg = 21.0, reversed = true, stepsPerDegree = 200))
        assertEquals(4, invalidations)
        guard.observe(idle.copy(connected = false))
        guard.observe(idle)
        assertEquals(6, invalidations)
    }
}
