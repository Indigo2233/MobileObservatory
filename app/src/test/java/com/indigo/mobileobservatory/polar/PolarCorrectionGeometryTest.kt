package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.astrometry.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.math.*

class PolarCorrectionGeometryTest {
    private val at = Instant.parse("2026-10-25T10:00:00Z")
    private val refraction = RefractionParameters(0.0, 15.0, 0.0)
    private fun determination(latitude: Double, azimuthError: Double = .4, altitudeError: Double = .3): PolarErrorDetermination {
        val site = ObserverSite(latitude, 110.0, 0.0)
        val axis = PolarVector3.fromTopocentric(TopocentricCoordinates(abs(latitude) + if (latitude > 0) altitudeError else -altitudeError,
            if (latitude > 0) azimuthError else 180.0 + azimuthError))
        val field = PolarVector3.fromTopocentric(TopocentricCoordinates(55.0, 35.0))
        val points = listOf(0.0, 15.0, 30.0).map {
            val ray = PolarVector3.rotateByRodrigues(field, axis, it)
            val sky = CoordinateTransform.topocentricToJ2000(ray.toTopocentric(), at, site, refraction)
            PolarSolvePoint(sky.raDeg, sky.decDeg, at)
        }
        return PolarAlignmentCalculator.determine(points, site, refraction)
    }
    private fun initialWcs(d: PolarErrorDetermination, parity: Int = -1, pixelScale: Double = .004): SolvedImageWcs = requireNotNull(SolvedImageWcs.parse(
        mapOf("PLTSOLVD" to "T", "CRVAL1" to "${d.initialReferenceFrame.raDeg}", "CRVAL2" to "${d.initialReferenceFrame.decDeg}",
            "CRPIX1" to "200.5", "CRPIX2" to "150.5", "CD1_1" to "${parity * pixelScale}", "CD1_2" to "0",
            "CD2_1" to "0", "CD2_2" to "$pixelScale"), 400, 300))
    private fun sample(wcs: SolvedImageWcs?) = PolarSample(phase = PolarSamplePhase.COMPLETE, file = File("third.fits"),
        result = PlateSolveResult(true, "Solved", imageWcs = wcs), capturedAt = at)

    @Test fun starTargetMovesOppositeToTheBoresightInBothHemispheresAndParities() {
        for (latitude in listOf(30.0, -33.0)) for (parity in listOf(-1, 1)) {
            val d = determination(latitude); val wcs = initialWcs(d, parity)
            val shape = requireNotNull(correctionGeometry(d, d.initial, sample(wcs)))
            assertEquals(199.5, shape.reference.x, .001); assertEquals(149.5, shape.reference.y, .001)
            val forward = ContinuousPolarErrorEstimator.coordinatesForResidual(
                EquatorialCoordinates(d.initialReferenceFrame.raDeg, d.initialReferenceFrame.decDeg),
                d.initial.azimuthErrorDeg, d.initial.altitudeErrorDeg, 0.0, 0.0, d.site, d.refraction, at)
            val destination = requireNotNull(wcs.pixel(forward)) - shape.reference
            val starMove = shape.target - shape.reference
            assertTrue(destination.x * starMove.x + destination.y * starMove.y < 0.0)
            val altitudeOnlyWcs = movedWcs(wcs, d, d.resultForResidual(d.initial.azimuthErrorDeg, 0.0))
            val altitudeOnly = requireNotNull(altitudeOnlyWcs.pixel(
                EquatorialCoordinates(d.initialReferenceFrame.raDeg, d.initialReferenceFrame.decDeg)))
            assertEquals(altitudeOnly.x, shape.altitude.x, .01)
            assertEquals(altitudeOnly.y, shape.altitude.y, .01)
        }
    }

    @Test fun referenceFollowsTheSameSkyPointTowardAStableTargetAndZeroErrorCollapsesIt() {
        val d = determination(30.0); val original = initialWcs(d)
        val skyReference = EquatorialCoordinates(d.initialReferenceFrame.raDeg, d.initialReferenceFrame.decDeg)
        val initial = requireNotNull(correctionGeometry(d, d.initial, sample(original)))
        var previousDistance = hypot(initial.target.x - initial.reference.x, initial.target.y - initial.reference.y)
        for (factor in listOf(.5, .1, 0.0)) {
            val residual = d.resultForResidual(d.initial.azimuthErrorDeg * factor, d.initial.altitudeErrorDeg * factor)
            val currentWcs = movedWcs(original, d, residual)
            val shape = requireNotNull(correctionGeometry(d, residual, sample(currentWcs)))
            val reference = requireNotNull(currentWcs.pixel(skyReference))
            assertEquals(reference.x, shape.reference.x, .001); assertEquals(reference.y, shape.reference.y, .001)
            assertEquals(initial.target.x, shape.target.x, .01)
            assertEquals(initial.target.y, shape.target.y, .01)
            val distance = hypot(shape.target.x - shape.reference.x, shape.target.y - shape.reference.y)
            assertTrue(distance < previousDistance); previousDistance = distance
            if (factor == 0.0) assertTrue(distance < .001)
        }
    }

    @Test fun snapshotKeepsItsScaleAsErrorFallsAndCanExpandAfterAnIncorrectAdjustment() {
        val d = determination(30.0)
        val original = initialWcs(d)
        val initial = PolarAdjustmentSnapshot.create(d, d.initial, sample(original))
        val reduced = d.resultForResidual(d.initial.azimuthErrorDeg / 2, d.initial.altitudeErrorDeg / 2)
        val smaller = PolarAdjustmentSnapshot.create(d, reduced, sample(movedWcs(original, d, reduced)), initial)
        assertEquals(initial.referenceDirection, smaller.referenceDirection)
        assertEquals(initial.reticleStepArcMin, smaller.reticleStepArcMin, 0.0)
        val increased = d.resultForResidual(d.initial.azimuthErrorDeg * 4, d.initial.altitudeErrorDeg * 4)
        val larger = PolarAdjustmentSnapshot.create(d, increased, sample(movedWcs(original, d, increased)), smaller)
        assertTrue(larger.reticleStepArcMin > initial.reticleStepArcMin)
        val zero = d.resultForResidual(0.0, 0.0)
        val finished = PolarAdjustmentSnapshot.create(d, zero, sample(movedWcs(original, d, zero)), larger)
        assertEquals(larger.reticleStepArcMin, finished.reticleStepArcMin, 0.0)
    }

    @Test fun missingWcsKeepsTheNumericResultWithoutInventingAnImageGuide() {
        val d = determination(30.0)
        val snapshot = PolarAdjustmentSnapshot.create(d, d.initial, sample(null))
        assertNull(snapshot.geometry); assertEquals(d.initial.totalErrorArcMin, snapshot.current.totalErrorArcMin, 0.0)
    }

    @Test fun degreeScaleErrorsKeepTheGuideOnTheLatestImageAfterTheOriginalFieldLeavesIt() {
        for (latitude in listOf(30.0, -33.0)) for (parity in listOf(-1, 1)) {
            val d = determination(latitude, 1.2, .6)
            val original = initialWcs(d, parity, .0004)
            val residual = d.resultForResidual(d.initial.azimuthErrorDeg * .2, d.initial.altitudeErrorDeg * .2)
            val currentWcs = movedWcs(original, d, residual)
            val oldReference = requireNotNull(currentWcs.pixel(EquatorialCoordinates(
                d.initialReferenceFrame.raDeg, d.initialReferenceFrame.decDeg)))
            assertTrue(oldReference.x !in 0.0..400.0 || oldReference.y !in 0.0..300.0)
            val currentSample = sample(currentWcs).copy(file = File("latest.fits"))
            val snapshot = PolarAdjustmentSnapshot.create(d, residual, currentSample)
            val guide = requireNotNull(snapshot.geometry)
            assertEquals(File("latest.fits"), snapshot.file)
            assertEquals(199.5, guide.reference.x, 1e-8); assertEquals(149.5, guide.reference.y, 1e-8)
            assertTrue(guide.targetOutsideImage)
        }
    }

    /** Build an independent camera WCS by physically rotating its centre and two neighbouring rays. */
    private fun movedWcs(original: SolvedImageWcs, d: PolarErrorDetermination, residual: PolarAlignmentResult): SolvedImageWcs {
        fun moved(p: ImagePoint) = ContinuousPolarErrorEstimator.coordinatesForResidual(original.direction(p),
            d.initial.azimuthErrorDeg, d.initial.altitudeErrorDeg, residual.azimuthErrorDeg, residual.altitudeErrorDeg,
            d.site, d.refraction, at)
        val center = moved(ImagePoint(199.5, 149.5))
        fun plane(sky: EquatorialCoordinates): ImagePoint {
            val r = Math.toRadians(sky.raDeg - center.raDeg); val dec = Math.toRadians(sky.decDeg); val dec0 = Math.toRadians(center.decDeg)
            val denominator = sin(dec0) * sin(dec) + cos(dec0) * cos(dec) * cos(r)
            return ImagePoint(Math.toDegrees(cos(dec) * sin(r) / denominator),
                Math.toDegrees((cos(dec0) * sin(dec) - sin(dec0) * cos(dec) * cos(r)) / denominator))
        }
        val x = plane(moved(ImagePoint(200.5, 149.5))); val y = plane(moved(ImagePoint(199.5, 150.5)))
        return requireNotNull(SolvedImageWcs.parse(mapOf("PLTSOLVD" to "T", "CRVAL1" to "${center.raDeg}", "CRVAL2" to "${center.decDeg}",
            "CRPIX1" to "200.5", "CRPIX2" to "150.5", "CD1_1" to "${x.x}", "CD1_2" to "${y.x}",
            "CD2_1" to "${x.y}", "CD2_2" to "${y.y}"), 400, 300))
    }
}
