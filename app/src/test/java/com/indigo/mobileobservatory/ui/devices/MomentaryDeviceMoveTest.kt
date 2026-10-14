package com.indigo.mobileobservatory.ui.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MomentaryDeviceMoveTest {
    @Test
    fun dismissingAnIdleControlDoesNotStopAPlannedMove() {
        var stops = 0
        val move = MomentaryDeviceMove(start = {}, stop = { stops++ })
        move.end()
        assertEquals(0, stops)
    }

    @Test
    fun releaseCancelLifecycleAndDisposeStopAnOwnedPressOnlyOnce() {
        var starts = 0
        var stops = 0
        val move = MomentaryDeviceMove(start = { starts++ }, stop = { stops++ })
        move.begin()
        move.begin()
        repeat(4) { move.end() }
        assertEquals(1, starts)
        assertEquals(1, stops)
        move.begin()
        move.end()
        assertEquals(2, starts)
        assertEquals(2, stops)
    }

    @Test
    fun partiallyStartedCommandIsStoppedBeforePropagatingFailure() {
        var stops = 0
        val move = MomentaryDeviceMove(
            start = { throw IllegalStateException("transport failed") },
            stop = { stops++ }
        )
        assertThrows(IllegalStateException::class.java) { move.begin() }
        move.end()
        assertEquals(1, stops)
    }
}
