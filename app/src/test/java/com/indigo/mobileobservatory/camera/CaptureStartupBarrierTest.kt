package com.indigo.mobileobservatory.camera

import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class CaptureStartupBarrierTest {
    @Test fun sdkBufferedOldImagesAndFirstInTransportImageCannotReachNewScienceRun() {
        val source = CaptureGeneration()
        val oldRun = source.begin(true)
        source.invalidateScience()
        source.stop()
        val sdkQueue = ArrayDeque(listOf("old exposure 1", "old exposure 2", "old exposure 3"))
        val discarded = mutableListOf<String>()
        val startup = CaptureStartupBarrier()
        val drained = startup.drain(nanoTime = { 0L }) {
            if (sdkQueue.isEmpty()) CaptureStartupBarrier.Read.EMPTY
            else { discarded += sdkQueue.removeFirst(); CaptureStartupBarrier.Read.BUFFERED_FRAME }
        }
        val newRun = source.begin(drained)
        assertTrue(drained)
        assertEquals(3, discarded.size)
        assertNotEquals(oldRun, newRun)
        // A frame already in transport at the observed empty boundary is discarded as well.
        assertFalse(startup.acceptCompleteFrame())
        assertTrue(startup.acceptCompleteFrame())
        assertEquals(newRun, source.scienceGeneration)
    }

    @Test fun aTimeoutDoesNotConsumeTheBoundaryFrameDiscard() {
        val startup = CaptureStartupBarrier()
        assertTrue(startup.drain { CaptureStartupBarrier.Read.EMPTY })
        // Adapter timeouts/errors never call acceptCompleteFrame: only complete images do.
        assertFalse(startup.acceptCompleteFrame())
        repeat(10) { assertTrue(startup.acceptCompleteFrame()) }
    }

    @Test fun readLimitAndErrorsNeverEstablishScientificProvenance() {
        val startup = CaptureStartupBarrier()
        var reads = 0
        assertFalse(startup.drain(maxReads = 4, nanoTime = { 0L }) {
            reads++; CaptureStartupBarrier.Read.BUFFERED_FRAME
        })
        assertEquals(4, reads)
        assertFalse(startup.drain { CaptureStartupBarrier.Read.FAILED })
        assertFalse(startup.drain { throw IllegalStateException("SDK read failure") })
        val source = CaptureGeneration()
        val run = source.begin(false)
        repeat(100) { startup.acceptCompleteFrame() }
        assertTrue(source.isActive(run)) // Ordinary preview is still usable.
        assertNull(source.scienceGeneration)
    }

    @Test fun elapsedLimitFailsEvenWhenAnOverlongSdkReadEventuallyReportsEmpty() {
        var now = 0L
        var reads = 0
        val startup = CaptureStartupBarrier()
        assertFalse(startup.drain(maxNanos = 10L, nanoTime = { now }) {
            reads++; now = 10L; CaptureStartupBarrier.Read.EMPTY
        })
        assertEquals(1, reads)
        now = 0L; reads = 0
        assertFalse(startup.drain(maxNanos = 10L, nanoTime = { now }) {
            reads++; now += 5; CaptureStartupBarrier.Read.BUFFERED_FRAME
        })
        assertEquals(2, reads)
    }

    @Test fun interruptedStartupDoesNotTouchSdkOrClaimEmpty() {
        Thread.currentThread().interrupt()
        try {
            assertFalse(CaptureStartupBarrier().drain { fail("Should not read SDK"); CaptureStartupBarrier.Read.EMPTY })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
