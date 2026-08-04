package com.indigo.mobileobservatory.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos

/**
 * Pins the apparent place reduction against ERFA, the reference implementation of the IAU SOFA
 * routines that NINA also relies on.
 *
 * The expected values were produced with `erfa.atco13` and `erfa.atoc13` for an observer at
 * latitude 40, longitude 0 and 250 m elevation, using DUT1 = 0 to match the UT1 approximation this
 * port makes.
 */
class CoordinateTransformTest {
    private data class Case(
        val instant: String,
        val raDeg: Double,
        val decDeg: Double,
        val observedAltitudeDeg: Double,
        val azimuthDeg: Double,
        val geometricAltitudeDeg: Double
    )

    private val site = ObserverSite(latitudeDeg = 40.0, longitudeDeg = 0.0, elevationMeters = 250.0)
    private val refraction = RefractionParameters(
        pressureHPa = 1005.0,
        temperatureC = 7.0,
        relativeHumidity = 0.8,
        wavelengthMicron = 0.55
    )

    private val cases = listOf(
        Case("2000-01-01T00:00:00Z", 186.417909440, 27.753756453, 20.0, 70.0, 19.9558504),
        Case("2026-03-21T22:15:30Z", 191.438031831, 88.903907494, 41.0, 1.0, 40.9813863),
        Case("2019-08-14T02:40:00Z", 1.984030836, 14.884287372, 65.0, 180.0, 64.9924456),
        Case("2032-11-05T18:05:12Z", 242.414632165, 29.610083232, 30.0, 285.0, 29.9720263)
    )

    private val oneArcsecond = 1.0 / 3600.0

    @Test
    fun matchesErfaObservedPlace() {
        for (case in cases) {
            val topocentric = CoordinateTransform.j2000ToTopocentric(
                EquatorialCoordinates(case.raDeg, case.decDeg),
                Instant.parse(case.instant),
                site,
                refraction
            )
            assertEquals(
                "altitude for ${case.instant}",
                case.observedAltitudeDeg,
                topocentric.altitudeDeg,
                oneArcsecond
            )
            // Azimuth converges near the zenith, so compare the displacement on the sky.
            val azimuthOnSky =
                (topocentric.azimuthDeg - case.azimuthDeg) * cos(case.observedAltitudeDeg.toRad())
            assertTrue(
                "azimuth for ${case.instant} was off by $azimuthOnSky deg",
                abs(azimuthOnSky) < oneArcsecond
            )
        }
    }

    @Test
    fun matchesErfaGeometricPlaceWithoutRefraction() {
        for (case in cases) {
            val topocentric = CoordinateTransform.j2000ToTopocentric(
                EquatorialCoordinates(case.raDeg, case.decDeg),
                Instant.parse(case.instant),
                site,
                null
            )
            assertEquals(
                "geometric altitude for ${case.instant}",
                case.geometricAltitudeDeg,
                topocentric.altitudeDeg,
                oneArcsecond
            )
        }
    }

    @Test
    fun topocentricToJ2000InvertsTheForwardTransform() {
        for (case in cases) {
            val instant = Instant.parse(case.instant)
            val equatorial = CoordinateTransform.topocentricToJ2000(
                TopocentricCoordinates(case.observedAltitudeDeg, case.azimuthDeg),
                instant,
                site,
                refraction
            )
            assertEquals("ra for ${case.instant}", case.raDeg, equatorial.raDeg, 2.0 * oneArcsecond)
            assertEquals("dec for ${case.instant}", case.decDeg, equatorial.decDeg, oneArcsecond)
        }
    }

    @Test
    fun greenwichSiderealTimeMatchesErfa() {
        val instant = Instant.parse("2000-01-01T00:00:00Z")
        val nutation = Nutation.compute(AstroTime.centuriesTt(instant))
        assertEquals(
            99.967798748,
            AstroTime.greenwichMeanSiderealTime(instant).toDeg(),
            1.0e-6
        )
        assertEquals(
            99.964248757,
            AstroTime.greenwichApparentSiderealTime(instant, nutation).toDeg(),
            1.0e-5
        )
    }

    @Test
    fun refractionConstantsMatchSofaRefco() {
        val constants = Refraction.constants(
            RefractionParameters(1005.0, 7.0, 0.8, 0.574)
        )
        // SOFA iauRefco produces 58.243" and -0.0644" for these conditions, which is the 69.3"
        // of refraction at the pole that NINA's refracted-pole mode expects at latitude 40.
        assertEquals(58.2433, constants.a / ARCSEC_TO_RAD, 1.0e-3)
        assertEquals(-0.06441, constants.b / ARCSEC_TO_RAD, 1.0e-5)
        assertEquals(
            69.3,
            (Refraction.refractedAltitudeDeg(40.0, RefractionParameters(1005.0, 7.0, 0.8, 0.574)) - 40.0) * 3600.0,
            0.1
        )
    }
}
