package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AuxiliaryFrameCollectorTest {
    private fun frame(id: Long = 1, generation: Long? = 5) = FrameData(ByteArray(32 * 32) { 70 },
        32, 32, PixelFormat.MONO8, id, 2000, generation)
    @Test fun copiesSdkBufferBeforePreviewCanRecycleItAndKeepsFullBracket() = runBlocking {
        val stream = AuxiliaryFrameCollector(1000, 100_000, 1000)
        val original = frame()
        stream.accept(original, null, 2000, 101_000)
        original.data.fill(0)
        val sample = stream.receive()
        assertEquals(70, sample.frame.data[0].toInt())
        assertEquals(1500L, sample.stamp.elapsedMidpointMs)
        assertEquals(500L, sample.stamp.clockUncertaintyMs)
        assertEquals(100_500L, sample.stamp.utcMidpointMs)
        stream.close()
    }
    @Test fun changedGenerationAndClockCannotTurnOldFramesIntoFreshSamples() = runBlocking {
        val stream = AuxiliaryFrameCollector(1000, 100_000, 1000)
        stream.accept(frame(), null, 2000, 101_000); stream.receive()
        stream.accept(frame(2, 6), null, 3000, 102_000)
        assertTrue(runCatching { stream.receive() }.exceptionOrNull() is IllegalStateException)
        val clock = AuxiliaryFrameCollector(1000, 100_000, 1000)
        clock.accept(frame(), null, 2000, 102_000)
        assertTrue(runCatching { clock.receive() }.exceptionOrNull() is IllegalStateException)
    }
    @Test fun rapidlyDeliveredBufferedFramesKeepTheFreshRunExposureBound() = runBlocking {
        val stream = AuxiliaryFrameCollector(1000, 100_000, 1000)
        stream.accept(frame(), null, 5000, 104_000); stream.receive()
        stream.accept(frame(2), null, 5010, 104_010)
        val buffered = stream.receive()
        assertEquals(5000L, buffered.startElapsedMs)
        assertEquals(1000L, buffered.earliestExposureElapsedMs)
        assertEquals(3005L, buffered.stamp.elapsedMidpointMs)
        assertEquals(2005L, buffered.stamp.clockUncertaintyMs)
        assertEquals(102_005L, buffered.stamp.utcMidpointMs)
        stream.close()
    }
    @Test fun queueOverflowFailsInsteadOfSilentlySkippingCoverageAndCancelWakesWaiter() = runBlocking {
        val stream = AuxiliaryFrameCollector(1000, 100_000, 1000)
        for (id in 1L..3) stream.accept(frame(id), null, 1000 + id * 1000, 100_000 + id * 1000)
        stream.receive()
        assertTrue(runCatching { stream.receive() }.exceptionOrNull() is IllegalStateException)
        val canceled = AuxiliaryFrameCollector(1000, 100_000, 1000)
        canceled.close(); assertTrue(runCatching { canceled.receive() }.isFailure)
    }
}
