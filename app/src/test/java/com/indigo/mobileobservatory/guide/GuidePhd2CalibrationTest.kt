package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.mount.MountDirection
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
        assertTrue("RA return stays within outward motion including clearing",
            commands.filter { it.first == MountDirection.WEST }.sumOf { it.second } <=
                commands.filter { it.first == MountDirection.EAST }.sumOf { it.second })
    }

    @Test fun raStartsWithShortEastwardBacklashClearing() = runTest {
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
        assertEquals(MountDirection.EAST, commands.first().first)
        assertTrue("The first clearing pulse must be short", commands.first().second <= 100)
        assertTrue(commands.all { it.second in 1..5000 })
    }
}
