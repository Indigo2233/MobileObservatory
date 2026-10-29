package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.*
import org.junit.Assert.*
import org.junit.Test

class ScienceFrameProvenanceTest {
    private fun key(exposure: Double) = StackSessionKey("camera", 16, 16, PixelFormat.MONO16,
        Roi(0, 0, 16, 16), 1, 1, exposure, 10f, "L", "raw")
    private fun frame(run: Long?) = FrameData(ByteArray(512), 16, 16, PixelFormat.MONO16, 1, 1,
        captureGeneration = run)

    @Test fun queuedOldExposureCannotAcquireNewSettingsEvenAfterRestart() {
        val gate = ScienceFrameProvenance()
        gate.bind(11, key(1_000_000.0))
        assertEquals(1_000_000.0, gate.frame(frame(11), 11)!!.exposureUs, 0.0)
        gate.clear() // Setter invalidates before an asynchronous SDK write starts.
        assertNull(gate.latest(11))
        assertNull(gate.frame(frame(11), null))
        gate.bind(12, key(10_000_000.0))
        assertNull("A start action must wait for the new run's first frame", gate.latest(12))
        assertNull(gate.frame(frame(11), 12))
        assertEquals(10_000_000.0, gate.frame(frame(12), 12)!!.exposureUs, 0.0)
        assertEquals(10_000_000.0, gate.latest(12)!!.exposureUs, 0.0)
    }

    @Test fun unprovenFailedOrStoppedRunsNeverYieldCalibrationProvenance() {
        val gate = ScienceFrameProvenance()
        gate.bind(2, key(500_000.0))
        assertNull(gate.frame(frame(null), 2))
        assertNull(gate.frame(frame(2), null))
        assertNotNull(gate.frame(frame(2), 2))
        assertNull(gate.latest(null))
        gate.clear()
        assertNull(gate.frame(frame(2), 2))
    }

    @Test fun startupBoundaryFrameRemainsPreviewOnlyAfterLaterFramesAndBinning() {
        val startup = CaptureStartupBarrier()
        assertTrue(startup.drain { CaptureStartupBarrier.Read.EMPTY })
        val first = frame(12).copy(previewOnly = !startup.acceptCompleteFrame())
        val second = frame(12).copy(frameId = 2, previewOnly = !startup.acceptCompleteFrame())
        val gate = ScienceFrameProvenance()
        gate.bind(12, key(5_000_000.0))

        // A delayed first delivery retains its own classification after the reader gets frame 2.
        assertTrue(first.previewOnly)
        assertFalse(second.previewOnly)
        assertNull(gate.frame(first, 12))
        assertNull(gate.latest(12))
        assertNotNull(gate.frame(second, 12))
        assertNull(gate.frame(first, 12))

        val binning = SoftwareBinning().apply { factor = 2 }
        val display = binning.apply(first)
        assertTrue(display.previewOnly)
        assertNull(gate.frame(display, 12))
        assertTrue(binning.release(display.data))
    }
}
