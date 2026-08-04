package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.RefractionParameters
import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Regression tests for the three point polar axis solve.
 *
 * The reference values come from the NINA Three Point Polar Alignment plugin test suite, where they
 * were generated with Astropy and quaternion rotations. Reproducing them here keeps this port
 * honest about the full apparent place reduction, not just the plane fit.
 */
class PolarErrorDeterminationTest {
    private val site = ObserverSite(latitudeDeg = 40.0, longitudeDeg = 0.0, elevationMeters = 250.0)
    private val time: Instant = Instant.parse("2000-01-01T00:00:00Z")
    private val refraction = RefractionParameters(
        pressureHPa = 1005.0,
        temperatureC = 7.0,
        relativeHumidity = 0.8,
        wavelengthMicron = 0.574
    )

    private val arcsecond = 1.0 / 3600.0

    private fun determine(
        s1: Pair<Double, Double>,
        s2: Pair<Double, Double>,
        s3: Pair<Double, Double>,
        correctForRefraction: Boolean
    ): PolarErrorDetermination {
        val points = listOf(s3, s2, s1).map { PolarSolvePoint(it.first, it.second, time) }
        return PolarAlignmentCalculator.determine(
            points = points,
            site = site,
            refraction = refraction,
            correctForRefraction = correctForRefraction,
            referenceFrame = PolarSolvePoint(s1.first, s1.second, time)
        )
    }

    @Test
    fun veryDifferentAltitudePointsFromAstropyTruePole() {
        val error = determine(
            186.4193401 to 27.75369312,
            156.6798968 to 27.40124463,
            127.00972423 to 27.34989335,
            correctForRefraction = true
        )
        assertEquals(1.0, error.initial.altitudeErrorDeg, arcsecond)
        assertEquals(1.0, error.initial.azimuthErrorDeg, arcsecond)
    }

    @Test
    fun veryDifferentAltitudePointsFromAstropyRefractedPole() {
        val error = determine(
            186.4193401 to 27.75369312,
            156.6798968 to 27.40124463,
            127.00972423 to 27.34989335,
            correctForRefraction = false
        )
        assertEquals(1.0 - 69.3 / 3600.0, error.initial.altitudeErrorDeg, arcsecond)
        assertEquals(1.0, error.initial.azimuthErrorDeg, arcsecond)
    }

    @Test
    fun defaultPointsFromAstropyTruePole() {
        val error = determine(
            120.40437197 to 87.88183512,
            133.73661393 to 87.76679023,
            147.13178399 to 87.8022287,
            correctForRefraction = true
        )
        assertEquals(1.0, error.initial.altitudeErrorDeg, arcsecond)
        assertEquals(1.0, error.initial.azimuthErrorDeg, arcsecond)
    }

    @Test
    fun defaultPointsFromAstropyRefractedPole() {
        val error = determine(
            120.40437197 to 87.88183512,
            133.73661393 to 87.76679023,
            147.13178399 to 87.8022287,
            correctForRefraction = false
        )
        assertEquals(1.0 - 69.0 / 3600.0, error.initial.altitudeErrorDeg, 2.0 * arcsecond)
        assertEquals(1.0, error.initial.azimuthErrorDeg, arcsecond)
    }

    @Test
    fun perfectlyAlignedMountReportsNoError() {
        val axis = TopocentricCoordinates(altitudeDeg = site.latitudeDeg, azimuthDeg = 0.0)
        val points = simulate(axis, TopocentricCoordinates(50.0, 100.0), listOf(0.0, -30.0, -60.0))

        val error = PolarAlignmentCalculator.determine(points, site, refraction)

        assertEquals(0.0, error.initial.altitudeErrorDeg, arcsecond)
        assertEquals(0.0, error.initial.azimuthErrorDeg, arcsecond)
        assertEquals(0.0, error.initial.totalErrorDeg, arcsecond)
    }

    @Test
    fun errorIsInvariantToPointOrder() {
        val axis = TopocentricCoordinates(altitudeDeg = site.latitudeDeg + 0.4, azimuthDeg = 359.3)
        val points = simulate(axis, TopocentricCoordinates(45.0, 80.0), listOf(0.0, -20.0, -40.0))

        val forward = PolarAlignmentCalculator.determine(points, site, refraction)
        val reversed = PolarAlignmentCalculator.determine(points.reversed(), site, refraction)

        assertEquals(
            forward.initial.altitudeErrorDeg,
            reversed.initial.altitudeErrorDeg,
            arcsecond
        )
        assertEquals(forward.initial.azimuthErrorDeg, reversed.initial.azimuthErrorDeg, arcsecond)
    }

    @Test
    fun correctionFieldNearEastIsFlagged() {
        val axis = TopocentricCoordinates(altitudeDeg = site.latitudeDeg, azimuthDeg = 0.0)
        val points = simulate(axis, TopocentricCoordinates(50.0, 100.0), listOf(0.0, -30.0, -60.0))
        val determination = PolarAlignmentCalculator.determine(points, site, refraction)

        val nearEast = CoordinateTransform.topocentricToJ2000(
            TopocentricCoordinates(45.0, 94.5), time, site, refraction
        )
        val farFromEast = CoordinateTransform.topocentricToJ2000(
            TopocentricCoordinates(45.0, 96.0), time, site, refraction
        )

        val near = determination.correctionFieldInfo(
            PolarSolvePoint(nearEast.raDeg, nearEast.decDeg, time)
        )
        val far = determination.correctionFieldInfo(
            PolarSolvePoint(farFromEast.raDeg, farFromEast.decDeg, time)
        )

        assertTrue(near.nearEastWest)
        assertEquals(4.5, near.distanceToEastWestDeg, 0.05)
        assertFalse(far.nearEastWest)
        assertEquals(6.0, far.distanceToEastWestDeg, 0.05)
    }

    /**
     * Builds three plate solves by rotating a field around a known mount axis, then converting the
     * observed positions back to J2000. Feeding them into the solver must recover that axis.
     */
    private fun simulate(
        axis: TopocentricCoordinates,
        firstField: TopocentricCoordinates,
        rotationsDeg: List<Double>
    ): List<PolarSolvePoint> {
        val axisVector = PolarVector3.fromTopocentric(axis)
        val fieldVector = PolarVector3.fromTopocentric(firstField)
        return rotationsDeg.map { rotation ->
            val rotated = PolarVector3.rotateByRodrigues(fieldVector, axisVector, rotation)
            val equatorial = CoordinateTransform.topocentricToJ2000(
                rotated.toTopocentric(), time, site, refraction
            )
            PolarSolvePoint(equatorial.raDeg, equatorial.decDeg, time)
        }
    }
}
