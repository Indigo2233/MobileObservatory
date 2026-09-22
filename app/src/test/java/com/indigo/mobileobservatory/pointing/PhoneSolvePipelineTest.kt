package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.PhoneManualExposure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchStarSelectorTest {
    @Test
    fun prefersStarsSpreadAcrossTheFrame() {
        val clustered = (0 until 10).map { index ->
            ExtractedStar(8f + index, 8f + index * 0.2f, 400f, 80f, 80f - index, 10f)
        }
        val far = ExtractedStar(90f, 90f, 120f, 40f, 20f, 10f)
        val selected = MatchStarSelector.spread(clustered + far, limit = 6)
        assertTrue(selected.any { it.x > 50f && it.y > 50f })
    }
}

class AttitudeTimelineTest {
    @Test
    fun interpolatesOpticalAxisBetweenSamples() {
        val timeline = AttitudeTimeline()
        timeline.add(AttitudeSnapshot(1_000, Direction3.fromAltAz(10.0, 0.0)))
        timeline.add(AttitudeSnapshot(3_000, Direction3.fromAltAz(30.0, 0.0)))
        val mid = timeline.sample(2_000)!!
        val (alt, _) = mid.opticalAxis.toAltAz()
        assertEquals(20.0, alt, 1.5)
    }
}

class PhoneSolveCaptureLadderTest {
    @Test
    fun fewStarsBumpsBurstThenIso() {
        val first = PhoneSolveCaptureLadder.next(
            current = PhoneCaptureAttempt(1.0, PhoneManualExposure.ISO_AUTO, 1),
            failure = WideFieldSolveFailure.INSUFFICIENT_STARS,
            starCount = 3,
            attemptIndex = 0,
            maxExposureSeconds = 2.0,
            minIso = 100,
            maxIso = 6400
        )
        assertEquals(4, first!!.burstFrameCount)

        val afterBurst = PhoneSolveCaptureLadder.next(
            current = PhoneCaptureAttempt(0.5, PhoneManualExposure.ISO_AUTO, 16),
            failure = WideFieldSolveFailure.INSUFFICIENT_STARS,
            starCount = 4,
            attemptIndex = 1,
            maxExposureSeconds = 0.5,
            minIso = 100,
            maxIso = 3200
        )
        assertEquals(3200, afterBurst!!.iso)
    }

    @Test
    fun crowdedUnmatchedFieldDoesNotAddLight() {
        val next = PhoneSolveCaptureLadder.next(
            current = PhoneCaptureAttempt(1.0, 0, 1),
            failure = WideFieldSolveFailure.NO_CANDIDATE,
            starCount = 40,
            attemptIndex = 0,
            maxExposureSeconds = 2.0,
            minIso = 100,
            maxIso = 3200
        )
        assertNull(next)
    }
}

class ShortExposureMotionStackTest {
    @Test
    fun dropsFramesThatMovedTooFarFromTheFirst() {
        val star = ByteArray(16) { 10 }
        star[5] = 120.toByte()
        val first = FrameData(star, 4, 4, PixelFormat.MONO8, 1, 1)
        val second = FrameData(star.copyOf(), 4, 4, PixelFormat.MONO8, 2, 2)
        val south = BurstAttitude(Direction3.fromAltAz(0.0, 180.0), Direction3(0.0, 0.0, 1.0))
        val east = BurstAttitude(Direction3.fromAltAz(0.0, 90.0), Direction3(0.0, 0.0, 1.0))
        val result = ShortExposureStacker.stack(
            frames = listOf(first, second),
            attitudes = listOf(south, east),
            fovWidthDeg = 70.0,
            fovHeightDeg = 53.0
        )
        assertEquals(1, result.rejectedMotionFrames)
        assertEquals(1, result.inputFrameCount)
        assertEquals(120, result.frame.data[5].toInt() and 0xFF)
    }
}
