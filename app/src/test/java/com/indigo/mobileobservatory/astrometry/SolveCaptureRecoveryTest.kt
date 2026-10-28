package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.polar.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SolveCaptureRecoveryTest {
    @Test fun narrowSearchThenBlindFallbackUseTheSameCapturedImage(): Unit = runTest {
        val calls = mutableListOf<Triple<File, MountCoordinates?, Double?>>()
        val engine = object : ImagePlateSolver {
            override val ready = true
            override suspend fun solve(inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?): PlateSolveResult {
                calls += Triple(inputFile, mountCoordinates, searchRadiusDeg)
                return PlateSolveResult(calls.size == 2, "No solution", log = "solve ${calls.size}", elapsedMs = 10)
            }
        }
        val image = File("single.fits")
        val hint = MountCoordinates(4.0, 35.0)
        val result = engine.solveWithFallback(image, 1.5, hint, 2.0, true)
        assertTrue(result.success)
        assertEquals(listOf(Triple(image, hint, 2.0), Triple(image, null, 180.0)), calls)
        assertEquals(20L, result.elapsedMs)
        assertTrue(result.log.contains("attempt=blind_fallback"))
        calls.clear()
        assertFalse(engine.solveWithFallback(image, 1.5, hint, 2.0, false).success)
        assertEquals(1, calls.size)
    }

    @Test fun transientFailureRetakesTheSameSlotWithoutMovingOrCompletingItEarly(): Unit = runTest {
        val session = PolarSampleSession()
        var captures = 0
        val failures = mutableListOf<PolarSample>()
        val result = retrySolveCapture(onRetry = { _, _ -> failures += session.samples.value[1] }) {
            session.execute(1, { File("capture-${++captures}.fits") }, { 1.5 }, { _, _ ->
                if (captures < 3) PlateSolveResult(false, "No solution", log = "Only 5 stars found in image. Abort")
                else PlateSolveResult(true, "Solved", raDeg = 60.0, decDeg = 35.0)
            })
        }
        assertTrue(result.success); assertEquals(3, captures)
        assertEquals(listOf(File("capture-1.fits"), File("capture-2.fits")), failures.map { it.file })
        assertTrue(failures.all { it.phase == PolarSamplePhase.FAILED })
        assertEquals(File("capture-3.fits"), session.samples.value[1].file)
        assertEquals(PolarSamplePhase.EMPTY, session.samples.value[2].phase)
    }

    @Test fun permanentFailuresNeverStartAnotherExposure(): Unit = runTest {
        for (reason in listOf("An ASTAP D20 or D50 database is not installed.", "ASTAP CLI is missing from native libraries.",
            "Cannot read image", "Not enough memory", "Focal length required", "CANNOT LINK EXECUTABLE", "Exec format error")) {
            var captures = 0
            retrySolveCapture { captures++; PlateSolveResult(false, reason) }
            assertEquals(reason, 1, captures)
        }
        var captures = 0
        val failure = runCatching { retrySolveCapture { captures++; error("USB disconnected") } }.exceptionOrNull()
        assertEquals("USB disconnected", failure?.message); assertEquals(1, captures)
    }

    @Test fun stopDuringRetryDelayPreventsAllFurtherCaptures(): Unit = runTest {
        var captures = 0
        val task = launch { retrySolveCapture { captures++; PlateSolveResult(false, "No solution") } }
        runCurrent(); assertEquals(1, captures)
        task.cancelAndJoin(); advanceUntilIdle(); assertEquals(1, captures)
    }

    @Test fun centeringRetryBudgetEndsWithTheLastFailure(): Unit = runTest {
        var captures = 0
        val result = retrySolveCapture(maxAttempts = 3) { captures++; PlateSolveResult(false, "failed $captures") }
        assertEquals(3, captures); assertEquals("failed 3", result.message)
    }

    @Test fun physicalDirectionsReverseWithThePole() {
        assertTrue(polarAdjustWest(.2, 30.0)); assertFalse(polarAdjustWest(-.2, 30.0))
        assertFalse(polarAdjustWest(.2, -33.0)); assertTrue(polarAdjustWest(-.2, -33.0))
        assertTrue(polarAdjustDown(.2, 30.0)); assertFalse(polarAdjustDown(-.2, 30.0))
        assertFalse(polarAdjustDown(.2, -33.0)); assertTrue(polarAdjustDown(-.2, -33.0))
    }
}
