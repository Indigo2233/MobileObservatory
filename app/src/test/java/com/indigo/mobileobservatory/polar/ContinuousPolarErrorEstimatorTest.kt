package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.RefractionParameters
import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import com.indigo.mobileobservatory.astro.normalize360
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Synthetic regression tests for the live correction estimator, mirroring the ones that ship with
 * NINA's Three Point Polar Alignment plugin.
 *
 * Each test builds a mount with a known initial error, projects the reference field forward under a
 * known residual error, and checks that the estimator recovers that residual.
 */
class ContinuousPolarErrorEstimatorTest {
    private val refraction = RefractionParameters(1005.0, 7.0, 0.8, 0.55)
    private val tenthArcsecond = 0.1 / 3600.0

    @Test
    fun exactRecoveryNorthernHemisphere() {
        val site = ObserverSite(48.0, 7.0, 250.0)
        val start = Instant.parse("2024-10-01T21:00:00Z")
        val determination = createDetermination(site, start, 35.0, 55.0, 1.2, -0.7)
        val frame = createCurrentFrame(determination, start.plus(Duration.ofMinutes(9)), 0.18, -0.11)

        val result = ContinuousPolarErrorEstimator.estimate(determination, frame, 0.7, -0.4)

        assertTrue(result.success)
        assertEquals(0.18, result.azimuthErrorDeg, tenthArcsecond)
        assertEquals(-0.11, result.altitudeErrorDeg, tenthArcsecond)
        assertTrue(result.residualArcSeconds < 0.1)
    }

    @Test
    fun exactRecoverySouthernHemisphere() {
        val site = ObserverSite(-33.0, 151.0, 40.0)
        val start = Instant.parse("2024-11-01T10:30:00Z")
        val determination = createDetermination(site, start, 210.0, 50.0, -0.9, 0.6)
        val frame = createCurrentFrame(determination, start.plus(Duration.ofMinutes(14)), -0.25, 0.15)

        val result = ContinuousPolarErrorEstimator.estimate(determination, frame, -0.9, 0.6)

        assertTrue(result.success)
        assertEquals(-0.25, result.azimuthErrorDeg, tenthArcsecond)
        assertEquals(0.15, result.altitudeErrorDeg, tenthArcsecond)
    }

    @Test
    fun reportsSmallerAzimuthResidualAfterPhysicalImprovement() {
        val site = ObserverSite(48.0, 7.0, 250.0)
        val start = Instant.parse("2024-10-01T21:00:00Z")
        val determination = createDetermination(site, start, 35.0, 55.0, 3.0, -0.4)
        val frame = createCurrentFrame(determination, start.plus(Duration.ofMinutes(9)), 2.0, -0.4)

        val result = ContinuousPolarErrorEstimator.estimate(determination, frame, 3.0, -0.4)

        assertTrue(result.success)
        assertTrue(result.azimuthErrorDeg < determination.initial.azimuthErrorDeg)
        assertEquals(2.0, result.azimuthErrorDeg, tenthArcsecond)
        assertEquals(-0.4, result.altitudeErrorDeg, tenthArcsecond)
    }

    @Test
    fun reportsSmallerSouthernAzimuthResidualAfterPhysicalImprovement() {
        val site = ObserverSite(-33.0, 151.0, 40.0)
        val start = Instant.parse("2024-11-01T10:30:00Z")
        val determination = createDetermination(site, start, 210.0, 50.0, -3.0, 0.4)
        val frame = createCurrentFrame(determination, start.plus(Duration.ofMinutes(14)), -2.0, 0.4)

        val result = ContinuousPolarErrorEstimator.estimate(determination, frame, -3.0, 0.4)

        assertTrue(result.success)
        assertTrue(abs(result.azimuthErrorDeg) < abs(determination.initial.azimuthErrorDeg))
        assertEquals(-2.0, result.azimuthErrorDeg, tenthArcsecond)
        assertEquals(0.4, result.altitudeErrorDeg, tenthArcsecond)
    }

    @Test
    fun residualZeroMeansFullyCorrected() {
        val site = ObserverSite(48.0, 7.0, 250.0)
        val start = Instant.parse("2024-10-01T21:00:00Z")
        val determination = createDetermination(site, start, 35.0, 55.0, 1.2, -0.7)
        val frame = createCurrentFrame(determination, start.plus(Duration.ofMinutes(4)), 0.0, 0.0)

        val result = ContinuousPolarErrorEstimator.estimate(determination, frame, 1.2, -0.7)

        assertTrue(result.success)
        assertEquals(0.0, result.azimuthErrorDeg, tenthArcsecond)
        assertEquals(0.0, result.altitudeErrorDeg, tenthArcsecond)
        assertEquals(
            0.0,
            determination.resultForResidual(result.azimuthErrorDeg, result.altitudeErrorDeg).totalErrorArcMin,
            0.01
        )
    }

    /** Builds a three point solve for a mount whose polar axis carries the requested error. */
    private fun createDetermination(
        site: ObserverSite,
        referenceTime: Instant,
        referenceAzimuthDeg: Double,
        referenceAltitudeDeg: Double,
        initialAzimuthErrorDeg: Double,
        initialAltitudeErrorDeg: Double
    ): PolarErrorDetermination {
        val northern = site.latitudeDeg > 0.0
        val poleAltitude = abs(site.latitudeDeg)
        val axisAzimuth = if (northern) {
            normalize360(initialAzimuthErrorDeg)
        } else {
            normalize360(initialAzimuthErrorDeg - 180.0)
        }
        val axisAltitude = if (northern) {
            poleAltitude + initialAltitudeErrorDeg
        } else {
            poleAltitude - initialAltitudeErrorDeg
        }

        val axisVector = PolarVector3.fromTopocentric(TopocentricCoordinates(axisAltitude, axisAzimuth))
        val third = PolarVector3.fromTopocentric(
            TopocentricCoordinates(referenceAltitudeDeg, referenceAzimuthDeg)
        )
        val second = PolarVector3.rotateByRodrigues(third, axisVector, 30.0)
        val first = PolarVector3.rotateByRodrigues(second, axisVector, 30.0)

        val points = listOf(first, second, third).map { vector ->
            val equatorial = CoordinateTransform.topocentricToJ2000(
                vector.toTopocentric(), referenceTime, site, refraction
            )
            PolarSolvePoint(equatorial.raDeg, equatorial.decDeg, referenceTime)
        }

        val determination = PolarAlignmentCalculator.determine(
            points = points,
            site = site,
            refraction = refraction,
            correctForRefraction = true,
            referenceFrame = points.last()
        )
        assertEquals(initialAzimuthErrorDeg, determination.initial.azimuthErrorDeg, 1.0 / 3600.0)
        assertEquals(initialAltitudeErrorDeg, determination.initial.altitudeErrorDeg, 1.0 / 3600.0)
        return determination
    }

    /** Projects the reference field forward under a chosen residual error. */
    private fun createCurrentFrame(
        determination: PolarErrorDetermination,
        observationTime: Instant,
        residualAzimuthErrorDeg: Double,
        residualAltitudeErrorDeg: Double
    ): PolarSolvePoint {
        val reference = determination.initialReferenceFrame
        val corrected = ContinuousPolarErrorEstimator.topocentricForResidual(
            EquatorialCoordinates(reference.raDeg, reference.decDeg),
            determination.initial.azimuthErrorDeg,
            determination.initial.altitudeErrorDeg,
            residualAzimuthErrorDeg,
            residualAltitudeErrorDeg,
            determination.site,
            determination.refraction,
            observationTime
        )
        val equatorial = CoordinateTransform.topocentricToJ2000(
            corrected, observationTime, determination.site, determination.refraction
        )
        return PolarSolvePoint(equatorial.raDeg, equatorial.decDeg, observationTime)
    }
}
