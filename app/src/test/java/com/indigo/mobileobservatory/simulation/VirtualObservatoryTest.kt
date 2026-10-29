package com.indigo.mobileobservatory.simulation

import com.indigo.mobileobservatory.astro.RefractionParameters
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.guide.*
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.polar.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

class VirtualObservatoryTest {
    private var time = 1_793_232_000_000L
    private fun model() = VirtualObservatory { time }.apply { driftPxPerSecond = 0.0; setPoleError(.8, .6) }

    @Test fun threePointGeometryRecoversTheSpecifiedPolarAxis() {
        val model = model()
        val points = (0..2).map { index ->
            if (index > 0) { model.start(MountDirection.EAST, false); time += 4000; model.stop() }
            val snapshot = model.snapshot()
            PolarSolvePoint(snapshot.center.raDeg, snapshot.center.decDeg, snapshot.at)
        }
        val result = PolarAlignmentCalculator.determine(points, model.site, RefractionParameters(pressureHPa = 0.0)).initial
        assertEquals(.8, result.azimuthErrorDeg, .01)
        assertEquals(.6, result.altitudeErrorDeg, .01)
    }

    @Test fun physicalPoleAdjustmentChangesTheCorrectionMeasurement() {
        val model = model()
        val points = (0..2).map { index ->
            if (index > 0) { model.start(MountDirection.EAST, false); time += 4000; model.stop() }
            model.snapshot().let { PolarSolvePoint(it.center.raDeg, it.center.decDeg, it.at) }
        }
        val initial = PolarAlignmentCalculator.determine(points, model.site, RefractionParameters(pressureHPa = 0.0))
        model.adjustPole(-.4, -.3)
        val frame = model.snapshot()
        val estimate = ContinuousPolarErrorEstimator.estimate(initial, PolarSolvePoint(frame.center.raDeg, frame.center.decDeg, frame.at),
            initial.initial.azimuthErrorDeg, initial.initial.altitudeErrorDeg)
        assertTrue(estimate.success)
        val actual = model.poleError()
        assertEquals(actual.first, estimate.azimuthErrorDeg, .03)
        assertEquals(actual.second, estimate.altitudeErrorDeg, .03)
    }

    @Test fun reversingDeclinationConsumesBacklashBeforeMotion() {
        val model = model()
        val start = model.snapshot()
        model.start(MountDirection.NORTH, true, 1000); time += 1000; model.stop()
        val slack = model.snapshot()
        assertEquals(start.y, slack.y, .1)
        model.start(MountDirection.NORTH, true, 1000); time += 1000; model.stop()
        assertTrue(kotlin.math.abs(model.snapshot().y - slack.y) > .5)
    }

    @Test fun pulseDeadlineAndStopBothBoundMotion() {
        val model = model()
        model.start(MountDirection.EAST, true, 100); time += 100
        val end = model.coordinates()
        time += 10_000
        assertEquals(end.raDeg, model.coordinates().raDeg, .000001)
        model.start(MountDirection.EAST, true); time += 50; model.stop()
        val stopped = model.coordinates()
        time += 10_000
        assertEquals(stopped.raDeg, model.coordinates().raDeg, .000001)
    }

    @Test fun hiddenStarsAndFailedSolvesCanBeRestored() {
        val model = model()
        model.failedSolvesRemaining = 2
        assertTrue(model.consumeSolveFailure()); assertTrue(model.consumeSolveFailure()); assertFalse(model.consumeSolveFailure())
        model.starsVisible = false; assertTrue(model.consumeSolveFailure())
        model.starsVisible = true; assertFalse(model.consumeSolveFailure())
    }

    @Test fun recordedImageAndVirtualMountCompleteRealCalibration() = runTest {
        val model = VirtualObservatory { time }
        val pixels = GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/guide/imx585-field.raw8.gz"))).use { it.readBytes() }
        // Use the same CFA-cell luminance conversion as the interactive session.
        for (y in 0 until 768 step 2) for (x in 0 until 1024 step 2) {
            val indices = listOf(y*1024+x, y*1024+x+1, (y+1)*1024+x, (y+1)*1024+x+1)
            val value = indices.sumOf { pixels[it].toInt() and 255 } / 4
            indices.forEach { pixels[it] = value.toByte() }
        }
        val camera = VirtualCamera(model, pixels, "test")
        val module = GuideModule()
        val result = GuideCalibrator(GuideCalibrationConfig(), pulse = { direction, ms ->
            model.start(direction, true, ms); time += ms; model.stop()
        }, measure = {
            // Match a 2 s guide exposure plus processing, including drift between pulses.
            time += 2_200
            module.usableStars(module.detectStars(FrameData(camera.render(model.snapshot()), 1024, 768, PixelFormat.MONO8, 0, time)))
        }).run().calibration
        assertTrue(result.eastSteps >= 7 && result.northSteps >= 7)
        assertTrue(result.fitResidualPx < .5f)
        assertEquals(1800f, result.decBacklashMs.toFloat(), 500f)
        camera.close()
    }
}
