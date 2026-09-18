package com.indigo.mobileobservatory.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyWatcherEquatorialMathTest {
    private val geometry = SkyWatcherMountGeometry(
        ra = SkyWatcherAxisGeometry(totalSteps = 12_960_000, timerFreq = 16_000_000, highSpeedRatio = 16),
        dec = SkyWatcherAxisGeometry(totalSteps = 12_960_000, timerFreq = 16_000_000, highSpeedRatio = 16)
    )

    @Test
    fun homeEncodersMapToPoleAndSixHourHa() {
        val eq = SkyWatcherEquatorialMath.encoderToEq(
            geometry = geometry,
            raSteps = geometry.raHomePosition,
            decSteps = geometry.decHomePosition,
            southernHemisphere = false
        )
        assertEquals(6.0, eq.hourAngleHours, 1e-6)
        assertEquals(90.0, eq.decDeg, 1e-6)
        assertEquals(SkyWatcherPierSide.WEST, eq.pierSide)
    }

    @Test
    fun northernGotoRoundTripsThroughEncoders() {
        val hourAngleHours = 2.0
        val decDeg = 45.0
        val target = SkyWatcherEquatorialMath.eqToEncoder(
            geometry = geometry,
            hourAngleHours = hourAngleHours,
            decDeg = decDeg,
            southernHemisphere = false
        )
        val restored = SkyWatcherEquatorialMath.encoderToEq(
            geometry = geometry,
            raSteps = target.raSteps,
            decSteps = target.decSteps,
            southernHemisphere = false
        )
        assertEquals(hourAngleHours, restored.hourAngleHours, 1e-4)
        assertEquals(decDeg, restored.decDeg, 1e-4)
    }

    @Test
    fun siderealRateUsesTimerFrequency() {
        val (turbo, code) = SkyWatcherEquatorialMath.rateCode(
            SkyWatcherEquatorialMath.SIDEREAL_ARCSEC_PER_SEC,
            geometry.ra
        )
        assertFalse(turbo)
        assertTrue(code > 1L)
        val (fastTurbo, _) = SkyWatcherEquatorialMath.rateCode(
            SkyWatcherEquatorialMath.SIDEREAL_ARCSEC_PER_SEC * 400.0,
            geometry.ra
        )
        assertTrue(fastTurbo)
    }

    @Test
    fun hourAngleWrapsAroundTwelveHours() {
        assertEquals(2.0, SkyWatcherEquatorialMath.hourAngleHours(3.0, 1.0), 1e-9)
        assertEquals(-2.0, SkyWatcherEquatorialMath.hourAngleHours(1.0, 3.0), 1e-9)
        assertEquals(0.0, SkyWatcherEquatorialMath.raHours(6.0, 6.0), 1e-9)
    }

    @Test
    fun altazHomeEncodersAreNorthHorizon() {
        val altAz = SkyWatcherEquatorialMath.encoderToAltAz(
            geometry,
            SkyWatcherMotorCodec.POSITION_OFFSET,
            SkyWatcherMotorCodec.POSITION_OFFSET
        )
        assertEquals(0.0, altAz.azimuthDeg, 1e-6)
        assertEquals(0.0, altAz.altitudeDeg, 1e-6)
    }

    @Test
    fun altazRoundTripsThroughHorizontalCoordinates() {
        val hourAngleHours = 2.0
        val decDeg = 45.0
        val latitude = 31.0
        val horizontal = SkyWatcherEquatorialMath.eqToHorizontal(hourAngleHours, decDeg, latitude)
        val restored = SkyWatcherEquatorialMath.horizontalToEq(horizontal, latitude)
        assertEquals(hourAngleHours, restored.first, 1e-4)
        assertEquals(decDeg, restored.second, 1e-4)
        val target = SkyWatcherEquatorialMath.altAzToEncoder(geometry, horizontal)
        val back = SkyWatcherEquatorialMath.encoderToAltAz(geometry, target.raSteps, target.decSteps)
        assertEquals(horizontal.azimuthDeg, back.azimuthDeg, 1e-3)
        assertEquals(horizontal.altitudeDeg, back.altitudeDeg, 1e-3)
    }

    @Test
    fun altazTrackingRatesAreFiniteAwayFromZenith() {
        val instant = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val (azRate, altRate) = SkyWatcherEquatorialMath.altazTrackingRatesArcsecPerSec(
            raHours = 3.0,
            decDeg = 20.0,
            latitudeDeg = 31.0,
            longitudeDeg = 121.0,
            instant = instant
        )
        assertTrue(kotlin.math.abs(azRate) < 12_000.0)
        assertTrue(kotlin.math.abs(altRate) < 12_000.0)
        assertTrue(kotlin.math.abs(azRate) > 0.0 || kotlin.math.abs(altRate) > 0.0)
    }
}
