package com.indigo.mobileobservatory.polar

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PolarExposureAndPreviewTest {
    @Test fun secondsReachTheCameraInMicrosecondsAndRespectItsSingleFrameRange() {
        assertEquals(3_000_000f, PolarExposure.parseUs("3", 10f, 20_000_000f)!!, .1f)
        assertEquals(500_000f, PolarExposure.parseUs("0,5", 10f, 20_000_000f)!!, .1f)
        for (value in listOf("", "NaN", "Infinity", "0", "-1", "21", "0.000001")) {
            assertNull(value, PolarExposure.parseUs(value, 10f, 20_000_000f))
        }
        assertEquals("3", PolarExposure.secondsText(3_000_000f))
    }

    @Test fun anIgnoredSdkWriteCannotBePresentedAsTheRequestedExposure() {
        assertTrue(PolarExposure.matchesReadback(3_000_000f, 3_000_001f))
        assertFalse(PolarExposure.matchesReadback(3_000_000f, 5_000_000f))
        assertFalse(PolarExposure.matchesReadback(3_000_000f, Float.NaN))
        assertFalse(PolarExposure.matchesReadback(3_000_000f, 0f))
    }

    @Test fun aTestImageNeedsNoSolverAndDoesNotCompleteAnAlignmentPoint(): Unit = runTest {
        val session = PolarSampleSession()
        val file = File("preview.fits")
        assertEquals(file, session.capturePreview(capture = { file }))
        assertTrue(session.samples.value.take(3).all { it.phase == PolarSamplePhase.EMPTY })
        assertEquals(file, session.samples.value.maxBy { it.displaySequence }.file)
        assertNull(session.samples.value[PolarSampleSession.PREVIEW_INDEX].result)
        session.execute(0, { File("sample.fits") }, { 1.5 }, { _, _ -> com.indigo.mobileobservatory.astrometry.PlateSolveResult(false, "Failed") })
        assertEquals(File("sample.fits"), session.samples.value.maxBy { it.displaySequence }.file)
    }

    @Test fun stoppingACameraOnlyTaskNeverSendsMountCommands(): Unit = runTest {
        var stops = 0
        val task = PolarWorkflowTask(this) { stops++ }
        assertTrue(task.start(stopMotionOnFinish = false) { delay(10_000) })
        runCurrent(); task.stop(); advanceUntilIdle()
        assertEquals(0, stops); assertFalse(task.busy.value)
    }
}
