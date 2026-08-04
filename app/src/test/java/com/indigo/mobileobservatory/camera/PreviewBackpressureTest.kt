package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewBackpressureTest {
    @Test
    fun newestFrameReplacesPendingFrame() {
        val slot = LatestFrameSlot<Int>()

        repeat(100) { slot.offer(it) }

        assertEquals(99, slot.takeLatest())
        assertEquals(100, slot.received)
        assertEquals(99, slot.dropped)
        assertNull(slot.takeLatest())
    }

    @Test
    fun consumingEachFrameDoesNotCountDrops() {
        val slot = LatestFrameSlot<Int>()

        repeat(100) {
            slot.offer(it)
            assertEquals(it, slot.takeLatest())
        }

        assertEquals(0, slot.dropped)
    }
}