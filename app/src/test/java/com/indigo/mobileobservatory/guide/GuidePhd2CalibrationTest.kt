package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountSlewRate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** A moving sky continues drifting during both guide pulses and complete exposures. */
class GuidePhd2CalibrationTest {
    @Test fun moderatePolarDriftDoesNotRequireReturningToTheOriginalTwoDimensionalPixel() = runTest {
        var x = 200f; var y = 200f
        val commands = mutableListOf<Pair<MountDirection, Int>>()
        val config = GuideCalibrationConfig(7, 15f, 1200)
        val result = GuideCalibrator(config, pulse = { direction, ms ->
            commands += direction to ms
            when (direction) {
                MountDirection.EAST -> x += ms * .004f
                MountDirection.WEST -> x -= ms * .004f
                MountDirection.NORTH -> y += ms * .006f
                MountDirection.SOUTH -> y -= ms * .006f
            }
            y += ms * .00004f
        }, measure = {
            y += .04f // one complete second-long exposure between pulses
            listOf(GuideStar(x, y, 30f, 200f))
        }).run()
        assertTrue(result.calibration.eastRatePxPerSec > 0f)
        assertTrue(result.calibration.northRatePxPerSec > 0f)
        assertTrue("Drift must not cause an unbounded return", commands.size < 50)
        assertTrue("RA returns the outward command time", kotlin.math.abs(
            commands.filter { it.first == MountDirection.EAST }.sumOf { it.second } -
                commands.filter { it.first == MountDirection.WEST }.sumOf { it.second }) <= 1)
    }

    @Test fun raStartsWestAndTheCalibrationPulseIsFixedUntilTheTravelTarget() = runTest {
        var x = 200f; var y = 200f
        val commands = mutableListOf<Pair<MountDirection, Int>>()
        GuideCalibrator(GuideCalibrationConfig(7, 15f, 1200), { direction, ms ->
            commands += direction to ms
            when (direction) {
                MountDirection.EAST -> x += ms * .004f
                MountDirection.WEST -> x -= ms * .004f
                MountDirection.NORTH -> y += ms * .006f
                MountDirection.SOUTH -> y -= ms * .006f
            }
        }, { listOf(GuideStar(x, y, 30f, 200f)) }).run()
        assertEquals(MountDirection.WEST, commands.first().first)
        assertTrue(commands.filter { it.first == MountDirection.WEST }.all { it.second == 1200 })
    }

    @Test fun cameraScaleDeterminesTheActualPulseOnBothAxes() = runTest {
        var x = 200f; var y = 200f
        val commands = mutableListOf<Pair<MountDirection, Int>>()
        val phases = mutableListOf<GuideCalibrationPhase>()
        val scale = requireNotNull(GuideStarAnalysis.arcsecPerPixel(2.9f, 220f))
        val config = GuideCalibrationConfig(7, 15f, 5000, scale, .5f)
        GuideCalibrator(config, { direction, ms ->
            commands += direction to ms
            when (direction) {
                MountDirection.WEST -> x -= ms * .004f
                MountDirection.EAST -> x += ms * .004f
                MountDirection.NORTH -> y += ms * .006f
                MountDirection.SOUTH -> y -= ms * .006f
            }
        }, { listOf(GuideStar(x, y, 30f, 200f)) }, {
            phases += it.phase
            if (it.phase == GuideCalibrationPhase.WEST || it.phase == GuideCalibrationPhase.NORTH) {
                assertEquals(800, commands.last().second)
            }
        }).run()
        assertEquals(GuideCalibrationPhase.WEST, phases.first())
        assertTrue(phases.indexOf(GuideCalibrationPhase.CLEAR_NORTH) >
            phases.lastIndexOf(GuideCalibrationPhase.EAST))
    }

    /**
     * The pulse estimate divides the requested travel by the commanded sidereal rate.
     * A mount that turns at a different rate than the estimate assumed scales every
     * step, so the calibration overshoots its travel target in fewer steps.
     */
    @Test fun theCalibrationReachesItsTravelTargetInTheConfiguredStepCount() = runTest {
        val scale = requireNotNull(GuideStarAnalysis.arcsecPerPixel(2.9f, 220f))
        val commandedRate = MountSlewRate.forGuideRateSidereal(.5f)
        val config = GuideCalibrationConfig(7, 15f, 1200, scale, commandedRate.guideRateMultiplier)
        // The mount turns at the commanded rate: 15 arcsec/s at 0.5x sidereal.
        val arcsecPerMs = 15.0 * commandedRate.guideRateMultiplier / 1000.0
        var x = 200f; var y = 200f
        val steps = mutableListOf<Float>()
        GuideCalibrator(config, { direction, ms ->
            when (direction) {
                MountDirection.WEST -> x -= (ms * arcsecPerMs / scale).toFloat()
                MountDirection.EAST -> x += (ms * arcsecPerMs / scale).toFloat()
                MountDirection.NORTH -> y += (ms * arcsecPerMs / scale).toFloat()
                MountDirection.SOUTH -> y -= (ms * arcsecPerMs / scale).toFloat()
            }
        }, { listOf(GuideStar(x, y, 30f, 200f)) }, {
            if (it.phase == GuideCalibrationPhase.WEST) steps += it.distancePx
        }).run()
        assertTrue("The outward steps must reach the requested travel", steps.isNotEmpty())
        assertTrue("The travel must not overshoot the target: $steps", steps.last() < 15f * 1.2f)
    }
}
