package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import com.indigo.mobileobservatory.astrometry.retrySolveCapture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.math.hypot

/** Deterministic virtual sky; exercises the workflow functions used by the actual screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class PolarSoftwareWorkflowTest {
    private val site = ObserverSite(30.0, 110.0)
    private val at = Instant.parse("2026-10-28T12:00:00Z")
    private val refraction = RefractionParameters(0.0, 15.0, 0.0)
    private val axis = PolarVector3.fromTopocentric(TopocentricCoordinates(30.6, 1.2))
    private val field = PolarVector3.fromTopocentric(TopocentricCoordinates(55.0, 35.0))
    private val points = listOf(0.0, 15.0, 30.0).map { rotation ->
        val sky = CoordinateTransform.topocentricToJ2000(
            PolarVector3.rotateByRodrigues(field, axis, rotation).toTopocentric(), at, site, refraction)
        PolarSolvePoint(sky.raDeg, sky.decDeg, at)
    }
    private fun determine(points: List<PolarSolvePoint>) = PolarAlignmentCalculator.determine(points, site, refraction)
    private fun adjusted(d: PolarErrorDetermination, az: Double, alt: Double, index: Int): PolarSolvePoint {
        val time = at.plusSeconds(60L + index * 10)
        val sky = ContinuousPolarErrorEstimator.coordinatesForResidual(
            EquatorialCoordinates(d.initialReferenceFrame.raDeg, d.initialReferenceFrame.decDeg),
            d.initial.azimuthErrorDeg, d.initial.altitudeErrorDeg, az, alt, site, refraction, time)
        return PolarSolvePoint(sky.raDeg, sky.decDeg, time)
    }

    @Test fun threePointsRetryInPlaceThenAutomaticallyMeasureConvergingAdjustment(): Unit = runTest {
        val session = PolarSampleSession()
        val events = mutableListOf<String>()
        val attempts = IntArray(3)
        val errors = mutableListOf<Double>()
        var completed = false
        runPolarAlignmentWorkflow(moveToPoint = { events += "position:$it" }, capturePoint = { index ->
            val result = retrySolveCapture {
                session.execute(index, {
                    events += "capture:$index:${++attempts[index]}"
                    delay(3000) // Simulated three-second exposure; virtual time makes the test immediate.
                    File("point-$index-${attempts[index]}.fits")
                }, { 1.5 }, { _, _ ->
                    if (index == 1 && attempts[index] == 1) PlateSolveResult(false, "Not enough stars")
                    else PlateSolveResult(true, "Solved", raDeg = points[index].raDeg, decDeg = points[index].decDeg)
                }, exposureTime = { at })
            }
            PolarSolvePoint(requireNotNull(result.raDeg), requireNotNull(result.decDeg), at)
        }, determine = ::determine, onDetermined = { d ->
            events += "determined"
            assertEquals(1.2, d.initial.azimuthErrorDeg, 1e-6)
            assertEquals(.6, d.initial.altitudeErrorDeg, 1e-6)
        }, adjust = { d ->
            events += "adjust"
            val residuals = listOf(.4 to .2, .1 to .05, .02 to .01, .02 to .01)
            var index = 0
            runPolarCorrectionPhase(d, 2.0, capture = {
                val i = index++; val (az, alt) = residuals[i]
                adjusted(d, az, alt, i)
            }, onEstimate = { result, _ -> errors += result.totalErrorArcMin },
                onUnstable = { fail("Known sky should yield a stable estimate") }, onWithinTolerance = { completed = true })
        })
        assertEquals(listOf("position:0", "capture:0:1", "position:1", "capture:1:1", "capture:1:2",
            "position:2", "capture:2:1", "determined", "adjust"), events)
        assertTrue(completed)
        assertEquals(4, errors.size)
        assertEquals(hypot(.1, .05) * 60, errors[1], .002)
        assertTrue(errors.last() < 2.0)
        assertEquals(13_500L, currentTime)
        assertTrue(session.samples.value.take(3).all { it.phase == PolarSamplePhase.COMPLETE })
    }

    @Test fun stopDuringRepeatedFailureCancelsTheWorkflowBeforeTheNextMountMove(): Unit = runTest {
        var captures = 0; val positions = mutableListOf<Int>(); var determined = false; var stops = 0
        val task = PolarWorkflowTask(this) { stops++ }
        task.start {
            runPolarAlignmentWorkflow(moveToPoint = { positions += it }, capturePoint = {
                retrySolveCapture { captures++; PlateSolveResult(false, "No solution") }
                error("Failure cannot produce a measurement")
            }, determine = ::determine, onDetermined = { determined = true }, adjust = { fail("Unexpected adjustment") })
        }
        runCurrent(); advanceTimeBy(1500); runCurrent()
        assertEquals(2, captures)
        task.stop(); advanceUntilIdle()
        assertEquals(2, captures); assertEquals(listOf(0), positions)
        assertFalse(determined); assertFalse(task.busy.value); assertTrue(stops > 0)
    }

    @Test fun mountTimeoutPreventsTheNextExposureAndPreservesCompletedPoints(): Unit = runTest {
        val captured = mutableListOf<Int>()
        val failure = runCatching {
            runPolarAlignmentWorkflow(moveToPoint = { if (it == 1) error("Mount movement timed out") },
                capturePoint = { captured += it; points[it] }, determine = ::determine,
                onDetermined = { fail("Incomplete calibration") }, adjust = { fail("Incomplete calibration") })
        }.exceptionOrNull()
        assertEquals("Mount movement timed out", failure?.message)
        assertEquals(listOf(0), captured)
    }

    @Test fun missingMeasurementBreaksTheConsecutiveToleranceConfirmation(): Unit = runTest {
        val d = determine(points); var captures = 0; var finished = false
        runPolarCorrectionPhase(d, 2.0, capture = {
            val i = captures++
            if (i == 1) null else adjusted(d, .02, .01, i)
        }, onEstimate = { _, _ -> }, onUnstable = {}, onWithinTolerance = { finished = true })
        assertTrue(finished); assertEquals(4, captures)
    }

    @Test fun zeroToleranceKeepsUpdatingUntilStop(): Unit = runTest {
        val d = determine(points); var updates = 0; val enough = CompletableDeferred<Unit>()
        val task = launch {
            runPolarCorrectionPhase(d, 0.0, capture = { delay(100); adjusted(d, 0.0, 0.0, updates) },
                onEstimate = { _, _ -> if (++updates == 4) enough.complete(Unit) }, onUnstable = {},
                onWithinTolerance = { fail("Zero tolerance means explicit stop") })
        }
        enough.await(); task.cancelAndJoin(); advanceUntilIdle(); assertEquals(4, updates)
    }

    @Test fun manualModeAfterThreePointsDoesNotTakeAFourthExposure(): Unit = runTest {
        var captures = 0
        runPolarCorrectionPhase(determine(points), 0.0, capture = { captures++; null },
            onEstimate = { _, _ -> fail("Unexpected estimate") }, onUnstable = {}, onWithinTolerance = {},
            shouldCapture = { false })
        assertEquals(0, captures)
    }

    @Test fun switchingToManualFinishesCurrentMeasurementAndStopsBeforeNextExposure(): Unit = runTest {
        val d = determine(points)
        var automatic = true; var captures = 0; var updates = 0
        runPolarCorrectionPhase(d, 0.0, capture = {
            captures++; delay(1000); automatic = false
            adjusted(d, .1, .05, 0)
        }, onEstimate = { _, _ -> updates++ }, onUnstable = {}, onWithinTolerance = {},
            shouldCapture = { automatic })
        assertEquals(1, captures)
        assertEquals(1, updates)
    }

    @Test fun switchingToManualAlsoStopsFailedFrameRetries(): Unit = runTest {
        var automatic = true; var captures = 0
        runPolarCorrectionPhase(determine(points), 0.0, capture = {
            captures++; automatic = false; null
        }, onEstimate = { _, _ -> fail("Invalid frame") }, onUnstable = {}, onWithinTolerance = {},
            shouldCapture = { automatic })
        assertEquals(1, captures)
    }
}
