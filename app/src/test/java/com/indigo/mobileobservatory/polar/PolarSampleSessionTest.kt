package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import com.indigo.mobileobservatory.astrometry.FitsSolveHints
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PolarSampleSessionTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun anOldCancelledScreenCannotReplaceANewerAttempt(): Unit = runTest {
        val session = PolarSampleSession()
        val old = launch {
            session.execute(0, { File("old.fits") }, { 1.5 }, { _, _ ->
                withContext(NonCancellable) { delay(1000) }
                PlateSolveResult(false, "old failure")
            })
        }
        runCurrent(); old.cancel()
        session.execute(0, { File("new.fits") }, { 1.5 }, { _, _ -> PlateSolveResult(false, "new failure", log = "new log") })
        advanceUntilIdle()
        assertEquals(File("new.fits"), session.samples.value[0].file)
        assertEquals("new log", session.samples.value[0].result!!.log)
        assertEquals(PolarSamplePhase.FAILED, session.samples.value[0].phase)
    }
    @Test fun retryingAFailedPositionDoesNotRepeatItsRaMove() {
        val complete = PolarSample(phase = PolarSamplePhase.COMPLETE)
        val failed = PolarSample(phase = PolarSamplePhase.FAILED)
        val samples = listOf(complete, failed, PolarSample())
        val retry = polarResumePlan(samples, positionedPoint = 1, canResume = true)
        assertEquals(1, retry.startIndex); assertFalse(retry.reset); assertFalse(retry.moveFirst)
        val next = polarResumePlan(listOf(complete, complete, PolarSample()), 1, true)
        assertEquals(2, next.startIndex); assertTrue(next.moveFirst)
        val interruptedMove = polarResumePlan(samples, 0, false)
        assertTrue(interruptedMove.reset); assertEquals(0, interruptedMove.startIndex)
        assertFalse(interruptedMove.moveFirst)
        assertTrue(polarResumePlan(listOf(failed, complete, PolarSample()), 1, true).reset)
    }
    @Test fun capturedGeometryOverridesAnUnknownOrFullSensorCatalogHeight() {
        for (catalogHeight in listOf(0, 6300)) {
            val configuration = PolarSolveConfiguration(200.0, 2.9, catalogHeight, false, 180.0)
            val fov = configuration.fieldOfView(FitsSolveHints(width = 3856, height = 2180))
            assertNotNull(fov)
            assertTrue(fov!! in 1.80..1.82)
        }
        val failure = PolarSample(phase = PolarSamplePhase.FAILED, failedPhase = PolarSamplePhase.SOLVING,
            result = PlateSolveResult(false, "ASTAP exit code 1", log = "Not enough stars. Found 7 stars."))
        assertEquals(PolarSampleAdvice.STARS, failure.advice())
    }
    @Test fun capturedSensorMetadataOverridesAPreviouslySelectedSensor() {
        val configuration = PolarSolveConfiguration(400.0, 3.76, 4168, false, 180.0)
        val captured = FitsSolveHints(width = 960, height = 540, pixelSizeUm = 2.9,
            focalLengthMm = 200.0, binning = 2)
        assertEquals(206.265 * 2.9 * 2 * 540 / 400 / 3600, configuration.fieldOfView(captured)!!, .00001)
    }
    @Test fun importedOpticsRemainAssociatedWithRetriesAndResetOnRecapture(): Unit = runTest {
        val session = PolarSampleSession()
        val imported = File("imported.fits")
        session.import(0, imported)
        repeat(2) {
            session.execute(0, { imported }, { 1.0 }, { _, _ -> PlateSolveResult(false, "No solution") })
            assertTrue(session.samples.value[0].imported)
        }
        session.execute(0, { File("recaptured.fits") }, { 1.0 }, { _, _ -> PlateSolveResult(false, "No solution") })
        assertFalse(session.samples.value[0].imported)
    }
    @Test fun connectedCameraResolvesFieldOfViewFromTheActualCapturedImage(): Unit = runTest {
        val session = PolarSampleSession()
        val image = File("point-1.fits")
        var captures = 0
        val result = session.execute(0,
            capture = { captures++; image },
            fieldOfView = { file -> if (file == image) 1.81 else null },
            solve = { file, fov ->
                assertEquals(image, file); assertEquals(1.81, fov, .0001)
                PlateSolveResult(false, "No WCS", log = "Not enough stars. Found 7 stars.")
            })
        assertFalse(result.success)
        assertEquals(1, captures)
        assertEquals(image, session.samples.value[0].file)
        assertEquals(result.log, session.samples.value[0].result!!.log)
        assertEquals(PolarSamplePhase.SOLVING, session.samples.value[0].failedPhase)
    }

    @Test fun captureFailureIsAttachedToThePointAndPreventsAnySolve(): Unit = runTest {
        val session = PolarSampleSession()
        try {
            session.execute(1, { throw IllegalStateException("USB disconnected") }, { 1.8 }, { _, _ -> fail("Must not solve"); error("unreachable") })
            fail("Capture failure lost")
        } catch (_: IllegalStateException) { }
        val sample = session.samples.value[1]
        assertEquals(PolarSamplePhase.FAILED, sample.phase)
        assertEquals(PolarSamplePhase.CAPTURING, sample.failedPhase)
        assertEquals("USB disconnected", sample.failure)
        assertNull(sample.file)
    }

    @Test fun invalidOpticsAndThrownSolverErrorsKeepTheCapturedImage(): Unit = runTest {
        val session = PolarSampleSession()
        val image = File("failed.fits")
        for (validOptics in listOf(false, true)) {
            try {
                session.execute(2, { image }, { if (validOptics) 1.2 else null }, { _, _ -> error("Solver could not start") })
                fail("Failure ignored")
            } catch (_: IllegalStateException) { }
            val sample = session.samples.value[2]
            assertEquals(image, sample.file)
            assertEquals(PolarSamplePhase.SOLVING, sample.failedPhase)
            assertTrue(sample.result!!.log.contains(if (validOptics) "Solver could not start" else "focal length"))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun stoppingTheWorkflowPreventsSubsequentCaptureAndMotion(): Unit = runTest {
        val session = PolarSampleSession()
        val actions = mutableListOf<String>()
        val task = PolarWorkflowTask(this) { actions += "stop" }
        assertTrue(task.start {
            session.execute(0, { File("point.fits") }, { 1.5 }, { _, _ ->
                actions += "solve"; delay(60_000); PlateSolveResult(true, "Solved", raDeg = 10.0, decDeg = 30.0)
            })
            actions += "move-next"
            session.execute(1, { actions += "capture-next"; File("next.fits") }, { 1.5 }, { _, _ -> error("unexpected") })
        })
        runCurrent()
        assertTrue(task.busy.value)
        task.stop()
        assertFalse(task.start { actions += "concurrent" })
        advanceUntilIdle()
        assertFalse(task.busy.value)
        assertFalse(actions.contains("move-next")); assertFalse(actions.contains("capture-next"))
        assertFalse(actions.contains("concurrent"))
        assertEquals(PolarSamplePhase.CANCELLED, session.samples.value[0].phase)
        assertEquals(File("point.fits"), session.samples.value[0].file)
        assertNull(session.samples.value[0].failure)
    }
}
