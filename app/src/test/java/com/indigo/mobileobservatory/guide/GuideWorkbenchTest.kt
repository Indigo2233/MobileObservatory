package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.mount.MountDirection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp

class GuideWorkbenchTest {
    @Test fun calibrationFitsMultipleStepsAndMeasuresReversalBacklash() = runTest {
        var x = 100f; var y = 100f
        var last: MountDirection? = null; var slack = 0
        val runner = GuideCalibrator(GuideCalibrationConfig(), pulse = { direction, ms ->
            if (last != direction) { slack = if (direction == MountDirection.SOUTH) 1800 else 600; last = direction }
            val motion = (ms - slack).coerceAtLeast(0); slack = (slack - ms).coerceAtLeast(0)
            when (direction) {
                MountDirection.EAST -> x += motion * .004f
                MountDirection.WEST -> x -= motion * .004f
                MountDirection.NORTH -> y += motion * .006f
                MountDirection.SOUTH -> y -= motion * .006f
            }
        }, measure = { listOf(GuideStar(x, y, 20f, 100f)) })
        val result = runner.run()
        assertEquals(.004f, result.calibration.eastXPerMs, .0001f)
        assertEquals(.006f, result.calibration.northYPerMs, .0001f)
        assertTrue(result.calibration.eastSteps >= 4); assertTrue(result.calibration.northSteps >= 4)
        assertTrue(result.calibration.eastTravelPx >= 15f); assertTrue(result.calibration.northTravelPx >= 15f)
        assertEquals(1800f, result.calibration.decBacklashMs.toFloat(), 160f)
        assertTrue(result.calibration.fitResidualPx < .1f)
    }

    @Test fun calibrationFailsWithNoMotionInsteadOfProducingAMatrix() = runTest {
        val runner = GuideCalibrator(GuideCalibrationConfig(), { _, _ -> }, { listOf(GuideStar(100f, 100f, 20f)) })
        try { runner.run(); fail("Stationary mount accepted") } catch (_: IllegalStateException) { }
    }

    @Test fun crossAxisReturnDriftRejectsCalibration() = runTest {
        assertUnsafeReturnRejected(crossAxisDrift = true)
    }

    @Test fun asymmetricForwardAndReverseRatesRejectCalibration() = runTest {
        assertUnsafeReturnRejected(crossAxisDrift = false)
    }

    private suspend fun assertUnsafeReturnRejected(crossAxisDrift: Boolean) {
        var x = 100f; var y = 100f
        val calibrator = GuideCalibrator(GuideCalibrationConfig(), { direction, ms ->
            when (direction) {
                MountDirection.EAST -> x += ms * .004f
                MountDirection.WEST -> {
                    x -= ms * if (crossAxisDrift) .004f else .002f
                    if (crossAxisDrift) y += ms * .002f
                }
                MountDirection.NORTH -> y += ms * .006f
                MountDirection.SOUTH -> y -= ms * .006f
            }
        }, { listOf(GuideStar(x, y, 20f, 100f)) })
        val failure = runCatching { calibrator.run() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        val expected = if (crossAxisDrift) "Calibration return drifted across the axis" else "Forward and reverse calibration rates disagree"
        assertEquals(expected, failure?.message)
    }

    @Test fun gaussianProfileMeasuresHalfFluxRadiusFromBackgroundSubtractedRawPixels() {
        val size = 64
        val frame = FrameData(ByteArray(size * size) { index ->
            val dx = index % size - 32.0; val dy = index / size - 32.0
            (10 + 180 * exp(-(dx * dx + dy * dy) / 8)).toInt().toByte()
        }, size, size, PixelFormat.BAYER_RG8, 1, 0)
        val profile = GuideStarAnalysis.measure(frame, GuideStar(32f, 32f, 20f))!!
        assertEquals(2.35f, profile.hfrPx, .3f)
        assertTrue(profile.flux > 4000); assertEquals(190f, profile.peak, .01f)
        assertTrue(profile.encircledEnergy.zipWithNext().all { it.first <= it.second })
        assertEquals(1f, profile.encircledEnergy.last(), .001f)
        assertEquals(profile.width * profile.height, profile.pixels.size)
    }

    @Test fun viewportCoordinatesStayInsideSourceAndRoundTripAfterZoomAndPan() {
        val viewport = GuideViewport(1000f, 600f, 640, 480, 2f, 35f, -17f)
        val screen = viewport.toScreen(173f, 219f)
        val source = viewport.toImage(screen.x, screen.y)!!
        assertEquals(173f, source.x, .001f); assertEquals(219f, source.y, .001f)
        assertNull(viewport.toImage(-5000f, -5000f))
        assertFalse(viewport.contains(641f, 20f))
    }

    @Test fun graphUsesFixedSampleSpacingAndTheSameWindowForBothPlots() {
        val history = (1..240).map { GuideHistoryPoint(it.toLong(), it.toFloat(), 0f) }
        val window = GuidePlotWindow(history, 100)
        assertEquals(100, window.points.size); assertEquals(141L, window.points.first().timestampMs)
        assertEquals(1f / 99, GuidePlotWindow(history.take(3), 100).xFraction(1), .00001f)
        assertEquals(2f / 99, GuidePlotWindow(history.take(3), 100).xFraction(2), .00001f)
        assertEquals(2.062648f, GuideStarAnalysis.arcsecPerPixel(3f, 300f)!!, .0001f)
        assertNull(GuideStarAnalysis.arcsecPerPixel(3f, 0f))
    }
}
