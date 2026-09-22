package com.indigo.mobileobservatory.pointing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionGateTest {
    @Test
    fun firstStillWindowIsDueForAlignment() {
        val gate = MotionGate()
        assertEquals(MotionPhase.STILL, gate.phase)
        assertTrue(gate.autoCaptureDue)
        assertTrue(gate.consumeAutoCapture())
        assertFalse(gate.consumeAutoCapture())
    }

    @Test
    fun doesNotCaptureWhileMoving() {
        val gate = MotionGate()
        gate.consumeAutoCapture()
        gate.ingest(8.0, 1_000)
        assertEquals(MotionPhase.MOVING, gate.phase)
        assertFalse(gate.consumeAutoCapture())
    }

    @Test
    fun pushThenHoldStillArmsAnotherCapture() {
        val gate = MotionGate(settleMs = 400)
        gate.consumeAutoCapture()
        gate.notifyCaptureFinished()

        var t = 1_000L
        gate.ingest(5.0, t)
        t += 200
        gate.ingest(5.0, t)
        assertEquals(MotionPhase.MOVING, gate.phase)
        assertTrue(gate.movedSinceCapture)

        t += 50
        gate.ingest(0.1, t)
        assertEquals(MotionPhase.SETTLING, gate.phase)
        t += 400
        gate.ingest(0.1, t)
        assertEquals(MotionPhase.STILL, gate.phase)
        assertTrue(gate.autoCaptureDue)
        assertTrue(gate.consumeAutoCapture())
    }

    @Test
    fun tinyJitterDoesNotCountAsAPush() {
        val gate = MotionGate(minPushDeg = 0.8, moveOnDegPerSec = 1.2)
        gate.consumeAutoCapture()
        gate.notifyCaptureFinished()
        gate.ingest(0.2, 1_000)
        gate.ingest(0.2, 1_200)
        assertEquals(MotionPhase.STILL, gate.phase)
        assertFalse(gate.movedSinceCapture)
        assertFalse(gate.autoCaptureDue)
    }
}

class PushToAutoSolveTest {
    @Test
    fun firstAlignmentFiresWhenStillAndDue() {
        assertTrue(
            PushToAutoSolve.shouldFire(
                phase = MotionPhase.STILL,
                autoCaptureDue = true,
                alreadySolved = false,
                onTarget = false,
                solving = false,
                nowMs = 2_000,
                lastSolveEndedMs = 0
            )
        )
    }

    @Test
    fun skipsWhileMovingOrOnTarget() {
        assertFalse(
            PushToAutoSolve.shouldFire(
                phase = MotionPhase.MOVING,
                autoCaptureDue = true,
                alreadySolved = true,
                onTarget = false,
                solving = false,
                nowMs = 5_000,
                lastSolveEndedMs = 0
            )
        )
        assertFalse(
            PushToAutoSolve.shouldFire(
                phase = MotionPhase.STILL,
                autoCaptureDue = true,
                alreadySolved = true,
                onTarget = true,
                solving = false,
                nowMs = 5_000,
                lastSolveEndedMs = 0
            )
        )
    }
}
