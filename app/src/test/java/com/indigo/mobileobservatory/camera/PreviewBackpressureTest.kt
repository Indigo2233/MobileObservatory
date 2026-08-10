package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewBackpressureTest {
    @Test
    fun newestFrameReplacesPendingFrame() {
        val slot = LatestFrameSlot<Int>()

        val discarded = mutableListOf<Int>()
        repeat(100) { value ->
            slot.offer(value)?.let(discarded::add)
        }

        assertEquals(99, slot.takeLatest())
        assertEquals(100, slot.received)
        assertEquals(99, slot.dropped)
        assertEquals((0 until 99).toList(), discarded)
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

    @Test
    fun clearReturnsPendingFrameForRelease() {
        val slot = LatestFrameSlot<Int>()

        slot.offer(42)

        assertEquals(42, slot.clear())
        assertNull(slot.clear())
    }
}
