package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuideSafetyTest {
    @Test fun cancellingAnRaPulseStopsItAndPreventsTheQueuedDecPulse() = runTest {
        val actions = mutableListOf<String>()
        val runner = GuideTaskRunner(this, { direction, durationMs -> actions += "start-$direction"; assertTrue(durationMs in 1..5000) }, {
            actions += "stopping-$it"; delay(250); actions += "stopped-$it"
        })
        assertTrue(runner.launch { pulse(MountDirection.EAST, 2000); pulse(MountDirection.NORTH, 2000) })
        runCurrent(); advanceTimeBy(100); runner.cancel(); runCurrent()
        assertTrue(runner.busy)
        assertFalse(runner.launch { pulse(MountDirection.WEST, 100) })
        advanceUntilIdle()
        assertFalse(runner.busy)
        assertEquals(listOf("start-EAST", "stopping-EAST", "stopped-EAST"), actions)
        assertTrue(runner.launch { pulse(MountDirection.WEST, 100) }); advanceUntilIdle()
        assertEquals("stopped-WEST", actions.last())
    }

    @Test fun cancellationWhileTheStartCommandIsPendingStillSendsAStop() = runTest {
        val actions = mutableListOf<String>()
        val runner = GuideTaskRunner(this, { _, _ -> actions += "start"; delay(2000) }, { actions += "stop" })
        runner.launch { pulse(MountDirection.EAST, 2000) }
        runCurrent(); runner.cancel(); advanceUntilIdle()
        assertEquals(listOf("start", "stop"), actions)
    }

    @Test fun transportFailureDuringStartStillStopsTheAxis() = runTest {
        var stops = 0; var failed = false
        val runner = GuideTaskRunner(this, { _, _ -> throw IllegalStateException("transport") }, { stops++ })
        runner.launch { try { pulse(MountDirection.NORTH, 100) } catch (_: IllegalStateException) { failed = true } }
        advanceUntilIdle()
        assertTrue(failed); assertEquals(1, stops)
    }

    @Test fun calibrationMeasurementsCannotUseOldProcessedStarsAfterANewerCallback() {
        val buffer = GuideMeasurements(); val run = buffer.startRun()
        val stars = listOf(GuideStar(10f, 10f, 12f))
        buffer.delivered(1, 10, 100, run)
        val boundary = buffer.boundary()
        buffer.delivered(1, 11, 1500, run)
        buffer.processed(1, 10, stars, run)
        assertNull(buffer.fresh(boundary, 1000))
        buffer.processed(1, 11, stars, run)
        assertEquals(1500L, buffer.fresh(boundary, 1000)!!.deliveredMs)
        assertNull(buffer.fresh(boundary, 1600))
        buffer.startRun()
        buffer.delivered(1, 12, 2000, run)
        assertNull(buffer.processed(1, 12, stars, run)); assertNull(buffer.latest)
    }

    @Test fun smallWellConditionedCalibrationRatesAreValid() {
        val module = GuideModule()
        val projected = module.projectError(GuideCalibration(.0005f, 0f, 0f, .0007f, 5000), 1f, -1.4f)
        assertNotNull(projected)
        assertEquals(-2000f, projected!!.first, .01f); assertEquals(2000f, projected.second, .01f)
        assertNull(module.projectError(GuideCalibration(.01f, 0f, .01f, .00001f, 1000), 1f, 1f))
        assertNull(module.projectError(GuideCalibration(Float.NaN, 0f, 0f, .01f, 1000), 1f, 1f))
    }

    @Test fun qualityGateRejectsHotSaturatedAndWeakStars() {
        val module = GuideModule()
        val valid = GuideStar(10f, 10f, 10f, 100f, 1.5f)
        val stars = listOf(valid, valid.copy(radiusPx = 0f), valid.copy(saturated = true), valid.copy(snr = 2f), valid.copy(x = Float.NaN))
        assertEquals(listOf(valid), module.usableStars(stars))
    }

    @Test fun aSingleMismatchedStarCannotBiasTheWholeGuideVector() {
        val module = GuideModule()
        val refs = listOf(GuideStar(10f, 12f, 10f, 100f), GuideStar(40f, 30f, 10f, 50f), GuideStar(80f, 80f, 10f, 20f))
        val current = listOf(refs[0].copy(x = 13f, y = 10f), refs[1].copy(x = 43f, y = 28f), refs[2].copy(x = 120f, y = 75f))
        val shift = module.matchShift(refs, current)!!
        assertEquals(3f, shift.x, .001f); assertEquals(-2f, shift.y, .001f)
    }

    @Test fun invalidatingCalibrationKeepsTheCameraMeasurementSessionUsable() {
        val buffer = GuideMeasurements(); val run = buffer.startRun()
        buffer.delivered(1, 1, 100, run); buffer.processed(1, 1, listOf(GuideStar(10f,10f,8f)), run)
        buffer.forgetSamples(); assertNull(buffer.latest)
        buffer.delivered(1, 2, 200, run)
        assertNotNull(buffer.processed(1, 2, listOf(GuideStar(10f,10f,8f)), run))
    }

    @Test fun rgb24DetectionReadsAllThreeChannelsAndRejectsTheHotPixel() {
        val width = 64; val pixels = ByteArray(width * width * 3) { 10 }
        for (y in 26..38) for (x in 26..38) {
            val value = (10 + 180 * kotlin.math.exp(-((x - 32) * (x - 32) + (y - 32) * (y - 32)) / 8.0)).toInt()
            for (c in 0..2) pixels[(y * width + x) * 3 + c] = value.toByte()
        }
        for (c in 0..2) pixels[(10 * width + 10) * 3 + c] = 240.toByte()
        val module = GuideModule()
        val stars = module.usableStars(module.detectStars(FrameData(pixels, width, width, PixelFormat.RGB24, 1, 0)))
        assertEquals(1, stars.size); assertEquals(32f, stars.single().x, .2f); assertEquals(32f, stars.single().y, .2f)
    }

    @Test fun manyHotPixelsCannotConsumeAllUsableStarSlots() {
        val width = 256
        val pixels = ByteArray(width * width) { 10 }
        for (y in 12..112 step 25) for (x in 12..112 step 25) pixels[y * width + x] = 240.toByte()
        for (y in 204..236) for (x in 204..236) pixels[y * width + x] =
            (10 + 180 * kotlin.math.exp(-((x - 220) * (x - 220) + (y - 220) * (y - 220)) / 8.0)).toInt().toByte()
        val module = GuideModule()
        val stars = module.usableStars(module.detectStars(FrameData(pixels, width, width, PixelFormat.MONO8, 1, 0)))
        assertEquals(1, stars.size)
        assertEquals(220f, stars.single().x, .2f)
        assertEquals(220f, stars.single().y, .2f)
    }
}
