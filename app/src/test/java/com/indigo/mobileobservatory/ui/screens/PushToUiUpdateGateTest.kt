package com.indigo.mobileobservatory.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushToUiUpdateGateTest {
    @Test fun publishesAtMostAtConfiguredUiRate() {
        val gate = PushToUiUpdateGate(66)
        assertTrue(gate.shouldPublish(0))
        assertFalse(gate.shouldPublish(20))
        assertFalse(gate.shouldPublish(60))
        assertTrue(gate.shouldPublish(66))
        assertFalse(gate.shouldPublish(100))
        assertTrue(gate.shouldPublish(132))
    }

    @Test fun resetAndClockDiscontinuityPublishImmediately() {
        val gate = PushToUiUpdateGate(100)
        assertTrue(gate.shouldPublish(500))
        assertFalse(gate.shouldPublish(550))
        gate.reset()
        assertTrue(gate.shouldPublish(560))
        assertTrue(gate.shouldPublish(10))
    }
}
