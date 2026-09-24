package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.astro.ObserverSite
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceEphemerisTest {
    private val beijing = ObserverSite(39.9, 116.4)

    @Test
    fun `sun declination is near zero at the 2024 equinox`() {
        val sun = SequenceEphemeris.sunEquatorial(Instant.parse("2024-03-20T03:06:00Z"))
        assertEquals(0.0, sun.decDeg, 1.2)
    }

    @Test
    fun `sun declination is near the tropic at the 2024 June solstice`() {
        val sun = SequenceEphemeris.sunEquatorial(Instant.parse("2024-06-20T20:51:00Z"))
        assertEquals(23.44, sun.decDeg, 0.8)
    }

    @Test
    fun `beijing sunset on a june evening is after local noon`() {
        val noon = Instant.parse("2024-06-21T04:00:00Z")
        val sunset = SequenceEphemeris.nextSunEvent(
            beijing,
            noon,
            SequenceEphemeris.SUNSET_ALTITUDE,
            rising = false
        )
        assertNotNull(sunset)
        val instant = Instant.ofEpochMilli(sunset!!)
        assertTrue(instant.isAfter(noon))
        assertTrue(instant.isBefore(noon.plusSeconds(16 * 3600)))
        val altitude = SequenceEphemeris.sunAltitudeDeg(instant, beijing)
        assertEquals(SequenceEphemeris.SUNSET_ALTITUDE, altitude, 0.4)
    }

    @Test
    fun `moon illumination is low at new moon and high at full moon`() {
        val newMoon = SequenceEphemeris.moonIlluminationPct(Instant.parse("2024-03-10T09:00:00Z"))
        val fullMoon = SequenceEphemeris.moonIlluminationPct(Instant.parse("2024-03-25T07:00:00Z"))
        assertTrue(newMoon < 20.0)
        assertTrue(fullMoon > 80.0)
    }

    @Test
    fun `tonight window follows sunset to sunrise when the site is known`() {
        val evening = Instant.parse("2024-06-21T12:00:00Z")
        val window = tonightWindow(evening, beijing.latitudeDeg, beijing.longitudeDeg)
        assertTrue(window.second.isAfter(window.first))
        val hours = (window.second.toEpochMilli() - window.first.toEpochMilli()) / 3_600_000.0
        assertTrue(hours in 6.0..16.0)
        assertTrue(!evening.isBefore(window.first) && !evening.isAfter(window.second))
    }

    @Test
    fun `alt az converts back to a nearby equatorial place`() {
        val instant = Instant.parse("2024-06-21T12:00:00Z")
        val sun = SequenceEphemeris.sunEquatorial(instant)
        val topo = com.indigo.mobileobservatory.astro.CoordinateTransform.j2000ToTopocentric(
            coordinates = sun,
            instant = instant,
            site = beijing,
            refraction = null
        )
        val recovered = SequenceEphemeris.altAzToEquatorialHours(
            topo.altitudeDeg,
            topo.azimuthDeg,
            beijing,
            instant
        )
        assertEquals(sun.raDeg / 15.0, recovered.first, 0.05)
        assertEquals(sun.decDeg, recovered.second, 0.8)
    }

    @Test
    fun `meridian window starts after the configured delay`() {
        val settings = SequenceSettings(minutesAfterMeridian = 10.0, maxMinutesAfterMeridian = 20.0)
        assertTrue(!meridianFlipDue(5.0, settings))
        assertTrue(!meridianFlipDue(-5.0, settings))
        assertTrue(meridianFlipDue(-12.0, settings))
        assertTrue(!meridianFlipDue(-25.0, settings))
    }

    @Test
    fun `next meridian is within a sidereal day`() {
        val now = Instant.parse("2024-06-21T12:00:00Z")
        val meridian = SequenceEphemeris.nextMeridianMillis(0.0, beijing, now)
        val wait = meridian - now.toEpochMilli()
        assertTrue(wait > 30_000L)
        assertTrue(wait <= 24L * 3600L * 1000L)
    }
}
