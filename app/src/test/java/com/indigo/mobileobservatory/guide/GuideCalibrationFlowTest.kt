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
    @Test fun imx585At220MmRoundsCalibrationPulseUpToTheNext50Ms() {
        val scale = requireNotNull(GuideStarAnalysis.arcsecPerPixel(2.9f, 220f))
        val config = GuideCalibrationConfig(7, 15f, 1200, scale, .5f)
        assertEquals(800, config.pulseMs(true))
        assertEquals(800, config.pulseMs(false))
        assertEquals(800, config.copy(declinationDeg = -10.45f).pulseMs(true))
    }

    @Test fun initialPulseUsesImageScaleStepsTravelGuideRateAndDeclination() {
        val config = GuideCalibrationConfig(7, 21f, 1200, 2f, .5f, 60f)
        assertEquals(1400, config.pulseMs(true))
        assertEquals(1400, config.pulseMs(false))
        assertEquals(800, config.copy(steps = 14).pulseMs(false))
        assertEquals(700, config.copy(guideRateSidereal = 1f).pulseMs(false))
        assertEquals(2800, config.copy(arcsecPerPixel = 4f).pulseMs(false))
        assertEquals(1200, config.copy(arcsecPerPixel = null).pulseMs(true))
    }

    private fun assertMsClose(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("Expected $expected ± $tolerance ms, got $actual", abs(expected - actual) <= tolerance)
    }
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
        assertEquals(GuideCalibrationPhase.WEST, calibration().first().phase)
    }

    @Test fun returnDistanceIsTheRemainingDistanceFromTheAxisOrigin() = runTest {
        val progress = calibration()
        for (phase in listOf(GuideCalibrationPhase.EAST, GuideCalibrationPhase.SOUTH)) {
            val distances = progress.filter { it.phase == phase }.map { it.distancePx }
            assertTrue("Return distances must decrease toward zero: $distances",
                distances.zipWithNext().all { (a, b) -> b <= a + .1f })
            assertTrue("Return is a bounded command-time operation", distances.isNotEmpty())
        }
    }

    @Test fun measuredReturnUsesFewerPulsesThanOutwardSampling() = runTest {
        val progress = calibration()
        assertTrue(progress.count { it.phase == GuideCalibrationPhase.EAST } <
            progress.count { it.phase == GuideCalibrationPhase.WEST })
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

    @Test fun profileShowsBothSidesOfTheUnmodifiedSaturatedCore() {
        val pixels = ByteArray(64 * 64) { index ->
            val x = index % 64 - 32; val y = index / 64 - 32
            if (abs(x) <= 2 && abs(y) <= 2) 255.toByte() else 10.toByte()
        }
        val profile = requireNotNull(GuideStarAnalysis.measure(
            FrameData(pixels, 64, 64, PixelFormat.MONO8, 1, 0), GuideStar(32f, 32f, 20f)))
        assertEquals(-12f, profile.horizontalOffsets.first(), 0f)
        assertEquals(0f, profile.horizontalOffsets[12], 0f)
        assertEquals(12f, profile.horizontalOffsets.last(), 0f)
        assertTrue(profile.saturated)
        assertEquals(listOf(255f, 255f, 255f, 255f, 255f), profile.horizontalIntensity.subList(10, 15))
        assertEquals(255f, profile.saturationLevel, 0f)
    }

    @Test fun selectedStarIsRetainedWhenOtherStarsTakeTheBrightestSlots() {
        val width = 400
        val pixels = ByteArray(width * width) { 10 }
        for (row in 0..4) for (column in 0..3) {
            val cx = 60 + column * 80; val cy = 45 + row * 70
            val amplitude = if (row == 4 && column == 3) 55 else 160
            for (dy in -7..7) for (dx in -7..7) {
                pixels[(cy + dy) * width + cx + dx] =
                    (10 + amplitude * exp(-(dx * dx + dy * dy) / 4.5)).roundToInt().toByte()
            }
        }
        val module = GuideModule()
        val frame = FrameData(pixels, width, width, PixelFormat.MONO8, 1, 0)
        val selected = requireNotNull(module.detectStars(frame, 32).minByOrNull {
            kotlin.math.hypot(it.x - 300f, it.y - 325f)
        })
        assertTrue(module.detectStars(frame, 4).none { abs(it.x - selected.x) < 2 && abs(it.y - selected.y) < 2 })
        val tracked = requireNotNull(module.matchStar(selected, module.detectStars(frame, 4, preferred = selected)))
        assertEquals(selected.x, tracked.x, .1f)
        assertEquals(selected.y, tracked.y, .1f)
    }
}
