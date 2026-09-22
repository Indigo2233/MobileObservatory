package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneManualExposureTest {
    @Test
    fun logSliderRoundTrips() {
        val min = 0.01f
        val max = 0.5f
        for (seconds in listOf(0.01f, 0.03f, 0.1f, 0.25f, 0.5f)) {
            val pos = PhoneManualExposure.toSlider(seconds, min, max)
            val back = PhoneManualExposure.fromSlider(pos, min, max)
            assertEquals(seconds.toDouble(), back.toDouble(), 0.002)
        }
        assertTrue(PhoneManualExposure.toSlider(0.03f, min, max) < 0.5f)
        assertTrue(PhoneManualExposure.toSlider(0.25f, min, max) > 0.5f)
    }

    @Test
    fun usesLongerFrameDurationThanAdvertisedExposure() {
        val advertised = 500_000_000L
        val frame = 2_000_000_000L
        val maxNs = PhoneManualExposure.usableMaxExposureNs(advertised, frame)
        assertEquals(2_000_000_000L, maxNs)
    }

    @Test
    fun staysAtAdvertisedWhenFrameDurationIsShorter() {
        val advertised = 500_000_000L
        assertEquals(advertised, PhoneManualExposure.usableMaxExposureNs(advertised, 33_000_000L))
    }

    @Test
    fun defaultBurstIsFourWhenLensCapsAtHalfSecond() {
        assertEquals(4, PhoneManualExposure.defaultBurstFrames(0.5f))
        assertEquals(1, PhoneManualExposure.defaultBurstFrames(2f))
    }

    @Test
    fun formatsSubSecondAsFraction() {
        assertEquals("1/30 s", PhoneManualExposure.formatSeconds(1f / 30f))
        assertEquals("0.50 s", PhoneManualExposure.formatSeconds(0.5f))
        assertEquals("2.0 s", PhoneManualExposure.formatSeconds(2f))
    }
}
