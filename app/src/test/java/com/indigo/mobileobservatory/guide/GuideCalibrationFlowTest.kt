package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.mount.MountDirection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

class GuideCalibrationFlowTest {
    private suspend fun calibration(): List<GuideCalibrationProgress> {
        var x = 100f; var y = 100f
        val progress = mutableListOf<GuideCalibrationProgress>()
        GuideCalibrator(GuideCalibrationConfig(), { direction, ms ->
            when (direction) {
                MountDirection.EAST -> x += ms * .004f
                MountDirection.WEST -> x -= ms * .004f
                MountDirection.NORTH -> y += ms * .006f
                MountDirection.SOUTH -> y -= ms * .006f
            }
        }, { listOf(GuideStar(x, y, 30f, 200f)) }, progress::add).run()
        return progress
    }

    @Test fun trackingRaStartsMeasuringWithoutBacklashPreload() = runTest {
        assertEquals(GuideCalibrationPhase.EAST, calibration().first().phase)
    }

    @Test fun returnDistanceIsTheRemainingDistanceFromTheAxisOrigin() = runTest {
        val progress = calibration()
        for (phase in listOf(GuideCalibrationPhase.WEST, GuideCalibrationPhase.SOUTH)) {
            val distances = progress.filter { it.phase == phase }.map { it.distancePx }
            assertTrue("Return distances must decrease toward zero: $distances",
                distances.zipWithNext().all { (a, b) -> b <= a + .1f })
            assertTrue("Final return distance must approach zero: $distances", abs(distances.last()) <= 1f)
        }
    }

    @Test fun measuredReturnUsesFewerPulsesThanOutwardSampling() = runTest {
        val progress = calibration()
        assertTrue(progress.count { it.phase == GuideCalibrationPhase.WEST } <
            progress.count { it.phase == GuideCalibrationPhase.EAST })
        assertTrue(progress.count { it.phase == GuideCalibrationPhase.SOUTH } <
            progress.count { it.phase == GuideCalibrationPhase.NORTH })
    }

    @Test fun samePsfHasConsistentHfrAcrossStellarBrightness() {
        val noise = java.util.Random(585)
        val residuals = DoubleArray(64 * 64) { noise.nextGaussian() * 3 }
        fun profile(amplitude: Double): GuideStarProfile {
            val pixels = ByteArray(64 * 64) { index ->
                val x = index % 64 - 32.0; val y = index / 64 - 32.0
                (50 + amplitude * exp(-(x * x + y * y) / 8) + residuals[index])
                    .roundToInt().coerceIn(0, 255).toByte()
            }
            return requireNotNull(GuideStarAnalysis.measure(
                FrameData(pixels, 64, 64, PixelFormat.MONO8, 1, 0), GuideStar(32f, 32f, 20f)))
        }
        val faint = profile(35.0); val bright = profile(150.0)
        assertEquals("Equal widths should have comparable HFR", bright.hfrPx, faint.hfrPx, .4f)
        assertEquals(2.35f, faint.hfrPx, .5f)
    }
}
