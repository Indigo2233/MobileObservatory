package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.mount.MountDirection
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GuideBacklashClearingTest {
    private data class Pulse(val direction: MountDirection, val ms: Int)
    private data class Run(val clearingTravel: Float, val firstNorthStepMs: Int,
        val clearPulses: List<Pulse>, val result: GuideCalibrationResult)

    private suspend fun simulate(initialMs: Int, northSlackMs: Int = 0, northRate: Float = .006f): Run {
        var x = 100f; var y = 100f
        var northSlack = northSlackMs
        val pulses = mutableListOf<Pulse>()
        val clearingPulses = mutableListOf<Pulse>()
        var clearingStartY: Float? = null
        var clearingTravel = 0f
        var firstNorthStepMs = 0
        val calibrator = GuideCalibrator(GuideCalibrationConfig(7, 15f, initialMs), pulse = { direction, ms ->
            pulses += Pulse(direction, ms)
            when (direction) {
                MountDirection.EAST -> x += ms * .004f
                MountDirection.WEST -> x -= ms * .004f
                MountDirection.NORTH -> {
                    if (clearingStartY == null) clearingStartY = y
                    val moving = (ms - northSlack).coerceAtLeast(0)
                    northSlack = (northSlack - ms).coerceAtLeast(0)
                    y += moving * northRate
                }
                MountDirection.SOUTH -> y -= ms * northRate
            }
        }, measure = { listOf(GuideStar(x, y, 20f, 100f)) }, progress = {
            if (it.phase == GuideCalibrationPhase.CLEAR_NORTH) {
                clearingTravel = y - requireNotNull(clearingStartY)
                clearingPulses += pulses.last()
            }
            if (it.phase == GuideCalibrationPhase.NORTH && it.step == 1) firstNorthStepMs = pulses.last().ms
        })
        val result = calibrator.run()
        return Run(clearingTravel, firstNorthStepMs, clearingPulses, result)
    }

    @Test fun defaultClearingUsesOnlyASmallPartOfTheCalibrationTravel() = runTest {
        val run = simulate(1200)
        assertTrue("Clearing moved ${run.clearingTravel} px for a 15 px calibration", run.clearingTravel <= 5f)
        assertTrue(run.clearPulses.all { it.ms <= 500 })
        assertEquals(.006f, run.result.calibration.northYPerMs, .0001f)
    }

    @Test fun aLongFallbackPulseDoesNotIncreaseClearingPulses() = runTest {
        val run = simulate(5000)
        assertTrue("Clearing moved ${run.clearingTravel} px", run.clearingTravel <= 5f)
        assertTrue(run.clearPulses.all { it.ms <= 500 })
        assertEquals("Formal measurement uses the fixed fallback pulse", 5000, run.firstNorthStepMs)
        assertEquals(.006f, run.result.calibration.northYPerMs, .0001f)
    }

    @Test fun largeSlackCanBeTakenUpWithShortPulsesBeforeFitting() = runTest {
        val run = simulate(1200, northSlackMs = 3000)
        assertTrue(run.clearPulses.sumOf { it.ms } >= 3000)
        assertTrue(run.clearPulses.all { it.ms <= 500 })
        assertTrue(run.clearingTravel in 1f..5f)
        assertEquals(.006f, run.result.calibration.northYPerMs, .0001f)
    }

    @Test fun firstFittedStepRespectsTheConfiguredPulseLimit() = runTest {
        val run = simulate(300, northRate = .001f)
        assertTrue("First fitted step exceeded 300 ms: ${run.firstNorthStepMs}", run.firstNorthStepMs <= 300)
        assertEquals(.001f, run.result.calibration.northYPerMs, .00001f)
    }

    @Test fun slowMotionCanAccumulateAcrossShortProbes() = runTest {
        val run = simulate(1200, northRate = .0005f)
        assertTrue(run.clearingTravel in 1f..5f)
        assertTrue(run.clearPulses.all { it.ms <= 500 })
        assertEquals(.0005f, run.result.calibration.northYPerMs, .00001f)
    }

    @Test fun directionReversalsCannotSatisfyClearingConfirmation() = runTest {
        var x = 100f; var y = 100f; var pulses = 0; var fitted = false
        val failure = runCatching {
            GuideCalibrator(GuideCalibrationConfig(), { direction, ms ->
                when (direction) {
                    MountDirection.WEST -> x -= ms * .004f
                    MountDirection.EAST -> x += ms * .004f
                    MountDirection.NORTH -> y += if (++pulses % 2 == 0) -1f else 1f
                    MountDirection.SOUTH -> y -= ms * .006f
                }
            }, { listOf(GuideStar(x, y, 20f, 100f)) },
                { if (it.phase == GuideCalibrationPhase.NORTH) fitted = true }).run()
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("No consistent motion while clearing backlash", failure?.message)
        assertFalse(fitted)
        assertTrue(pulses <= 80)
    }

    @Test fun excessiveClearingTravelStopsBeforeAnotherPulseOrFit() = runTest {
        var x = 100f; var y = 100f; var pulses = 0; var fitted = false
        val failure = runCatching {
            GuideCalibrator(GuideCalibrationConfig(7, 15f, 1200), { direction, ms ->
                when (direction) {
                    MountDirection.WEST -> x -= ms * .004f
                    MountDirection.EAST -> x += ms * .004f
                    MountDirection.NORTH -> { y += 8f; pulses++ }
                    MountDirection.SOUTH -> y -= ms * .006f
                }
            }, { listOf(GuideStar(x, y, 20f, 100f)) },
                { if (it.phase == GuideCalibrationPhase.NORTH) fitted = true }).run()
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().startsWith("Backlash clearing moved too far"))
        assertEquals(1, pulses)
        assertFalse(fitted)
    }

    @Test fun aStationaryDecAxisHasABoundedClearingBudget() = runTest {
        var x = 100f; var commanded = 0; var largest = 0
        val failure = runCatching {
            GuideCalibrator(GuideCalibrationConfig(initialPulseMs = 5000), { direction, ms ->
                when (direction) {
                    MountDirection.WEST -> x -= ms * .004f
                    MountDirection.EAST -> x += ms * .004f
                    MountDirection.NORTH -> { commanded += ms; largest = maxOf(largest, ms) }
                    MountDirection.SOUTH -> Unit
                }
            }, { listOf(GuideStar(x, 100f, 20f, 100f)) }).run()
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(commanded <= 20_000)
        assertTrue(largest <= 500)
    }

    @Test fun stoppingAfterAClearingMeasurementPreventsTheNextPulse() = runTest {
        var x = 100f; var y = 100f; var clearingPulses = 0
        val stopped = CompletableDeferred<Unit>()
        val task = launch {
            val context = currentCoroutineContext()
            GuideCalibrator(GuideCalibrationConfig(), { direction, ms ->
                when (direction) {
                    MountDirection.WEST -> x -= ms * .004f
                    MountDirection.EAST -> x += ms * .004f
                    MountDirection.NORTH -> { y += ms * .006f; clearingPulses++ }
                    MountDirection.SOUTH -> y -= ms * .006f
                }
            }, { listOf(GuideStar(x, y, 20f, 100f)) }, {
                if (it.phase == GuideCalibrationPhase.CLEAR_NORTH) {
                    context.cancel(); stopped.complete(Unit)
                }
                }).run()
        }
        stopped.await(); task.cancelAndJoin()
        assertEquals(1, clearingPulses)
    }
}
