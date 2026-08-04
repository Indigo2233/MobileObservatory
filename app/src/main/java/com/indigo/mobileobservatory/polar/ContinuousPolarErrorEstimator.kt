package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.RefractionParameters
import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import com.indigo.mobileobservatory.astro.normalizeSigned180
import com.indigo.mobileobservatory.astro.toRad
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Estimates the polar alignment error that is still left while the user turns the adjustment knobs.
 *
 * The three point routine only measures the starting error. Afterwards every new plate solve gives
 * the current field centre, and this class answers the inverse question: which residual azimuth and
 * altitude error would put the reference field exactly there? The two unknowns are solved with a
 * damped Gauss-Newton step, using the same forward model as the initial determination, namely a
 * rotation about the local vertical followed by a rotation about the matching horizontal axis.
 *
 * Ported from `ContinuousPolarErrorEstimator` in NINA's Three Point Polar Alignment plugin.
 */
object ContinuousPolarErrorEstimator {
    // Five arcseconds. Small enough for a clean derivative, large enough to avoid cancellation.
    private const val DERIVATIVE_STEP_DEG = 1.0 / 720.0
    private const val MAX_ITERATIONS = 20
    private const val MINIMUM_EIGENVALUE = 1e-12
    private const val MAXIMUM_CONDITION_NUMBER = 1e6
    private const val CONVERGENCE_THRESHOLD_DEG = 1.0 / 360_000.0

    data class EstimationResult(
        val success: Boolean,
        val azimuthErrorDeg: Double,
        val altitudeErrorDeg: Double,
        val conditionNumber: Double,
        val residualArcSeconds: Double
    )

    fun estimate(
        determination: PolarErrorDetermination,
        currentFrame: PolarSolvePoint,
        seedAzimuthErrorDeg: Double,
        seedAltitudeErrorDeg: Double
    ): EstimationResult {
        val site = determination.site
        val refraction = determination.refraction
        val observationTime = currentFrame.solvedAt

        val observed = CoordinateTransform.j2000ToTopocentric(
            EquatorialCoordinates(currentFrame.raDeg, currentFrame.decDeg),
            observationTime,
            site,
            refraction
        )

        var azimuthError = seedAzimuthErrorDeg
        var altitudeError = seedAltitudeErrorDeg
        var lambda = 1e-3
        var currentCost = cost(determination, observed, observationTime, azimuthError, altitudeError)
        var normalEquations: NormalEquations? = null

        for (iteration in 0 until MAX_ITERATIONS) {
            val system = buildNormalEquations(
                determination,
                observed,
                observationTime,
                azimuthError,
                altitudeError
            ) ?: return EstimationResult(false, azimuthError, altitudeError, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
            normalEquations = system

            val step = solve2x2(
                system.a00 + lambda,
                system.a01,
                system.a11 + lambda,
                -system.gradient0,
                -system.gradient1
            ) ?: return EstimationResult(
                false,
                azimuthError,
                altitudeError,
                system.conditionNumber,
                system.residualArcSeconds
            )

            if (max(abs(step.first), abs(step.second)) < CONVERGENCE_THRESHOLD_DEG) break

            var accepted = false
            var scale = 1.0
            while (scale >= 0.0625) {
                val candidateAzimuth = azimuthError + step.first * scale
                val candidateAltitude = altitudeError + step.second * scale
                val candidateCost =
                    cost(determination, observed, observationTime, candidateAzimuth, candidateAltitude)
                if (candidateCost < currentCost) {
                    azimuthError = candidateAzimuth
                    altitudeError = candidateAltitude
                    currentCost = candidateCost
                    lambda = max(lambda * 0.5, 1e-6)
                    accepted = true
                    break
                }
                scale *= 0.5
            }

            if (!accepted) {
                lambda *= 8.0
                if (lambda > 1e6) break
            }
        }

        val finalSystem = buildNormalEquations(
            determination,
            observed,
            observationTime,
            azimuthError,
            altitudeError
        ) ?: normalEquations

        if (finalSystem == null) {
            return EstimationResult(
                false,
                azimuthError,
                altitudeError,
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY
            )
        }

        return EstimationResult(
            success = finalSystem.conditionNumber <= MAXIMUM_CONDITION_NUMBER,
            azimuthErrorDeg = azimuthError,
            altitudeErrorDeg = altitudeError,
            conditionNumber = finalSystem.conditionNumber,
            residualArcSeconds = finalSystem.residualArcSeconds
        )
    }

    /**
     * Predicts where the reference field appears when the mount still carries the supplied residual
     * error. The rotation applied is the difference between the measured initial error and that
     * residual, so a residual equal to the initial error means nothing has been corrected yet.
     */
    fun topocentricForResidual(
        coordinates: EquatorialCoordinates,
        initialAzimuthErrorDeg: Double,
        initialAltitudeErrorDeg: Double,
        residualAzimuthErrorDeg: Double,
        residualAltitudeErrorDeg: Double,
        site: ObserverSite,
        refraction: RefractionParameters?,
        observationTime: Instant
    ): TopocentricCoordinates {
        val reference =
            CoordinateTransform.j2000ToTopocentric(coordinates, observationTime, site, refraction)
        val referenceVector = PolarVector3.fromTopocentric(reference)

        val azimuthRotation = initialAzimuthErrorDeg - residualAzimuthErrorDeg
        val altitudeRotation = initialAltitudeErrorDeg - residualAltitudeErrorDeg

        val azimuthAdjusted = PolarVector3.rotateByRodrigues(
            referenceVector,
            PolarErrorDetermination.ZENITH_AXIS,
            azimuthRotation
        )
        val altitudeAxis = PolarVector3.rotateByRodrigues(
            PolarErrorDetermination.WEST_AXIS,
            PolarErrorDetermination.ZENITH_AXIS,
            azimuthRotation
        )
        return PolarVector3.rotateByRodrigues(azimuthAdjusted, altitudeAxis, altitudeRotation)
            .toTopocentric()
    }

    /** Same forward model, converted back to J2000 so it can be drawn on top of a solved frame. */
    fun coordinatesForResidual(
        coordinates: EquatorialCoordinates,
        initialAzimuthErrorDeg: Double,
        initialAltitudeErrorDeg: Double,
        residualAzimuthErrorDeg: Double,
        residualAltitudeErrorDeg: Double,
        site: ObserverSite,
        refraction: RefractionParameters?,
        observationTime: Instant
    ): EquatorialCoordinates {
        val corrected = topocentricForResidual(
            coordinates,
            initialAzimuthErrorDeg,
            initialAltitudeErrorDeg,
            residualAzimuthErrorDeg,
            residualAltitudeErrorDeg,
            site,
            refraction,
            observationTime
        )
        return CoordinateTransform.topocentricToJ2000(corrected, observationTime, site, refraction)
    }

    private class NormalEquations(
        val a00: Double,
        val a01: Double,
        val a11: Double,
        val gradient0: Double,
        val gradient1: Double,
        val conditionNumber: Double,
        val residualArcSeconds: Double
    )

    private fun cost(
        determination: PolarErrorDetermination,
        observed: TopocentricCoordinates,
        observationTime: Instant,
        azimuthErrorDeg: Double,
        altitudeErrorDeg: Double
    ): Double {
        val residual = residual(determination, observed, observationTime, azimuthErrorDeg, altitudeErrorDeg)
            ?: return Double.POSITIVE_INFINITY
        return 0.5 * (residual.first * residual.first + residual.second * residual.second)
    }

    private fun buildNormalEquations(
        determination: PolarErrorDetermination,
        observed: TopocentricCoordinates,
        observationTime: Instant,
        azimuthErrorDeg: Double,
        altitudeErrorDeg: Double
    ): NormalEquations? {
        val residual =
            residual(determination, observed, observationTime, azimuthErrorDeg, altitudeErrorDeg)
                ?: return null

        val dAzimuth = differentiate(
            determination, observed, observationTime, azimuthErrorDeg, altitudeErrorDeg, true
        ) ?: return null
        val dAltitude = differentiate(
            determination, observed, observationTime, azimuthErrorDeg, altitudeErrorDeg, false
        ) ?: return null

        val a00 = dAzimuth.first * dAzimuth.first + dAzimuth.second * dAzimuth.second
        val a01 = dAzimuth.first * dAltitude.first + dAzimuth.second * dAltitude.second
        val a11 = dAltitude.first * dAltitude.first + dAltitude.second * dAltitude.second
        val gradient0 = dAzimuth.first * residual.first + dAzimuth.second * residual.second
        val gradient1 = dAltitude.first * residual.first + dAltitude.second * residual.second
        val residualArcSeconds =
            sqrt(residual.first * residual.first + residual.second * residual.second) * 3600.0

        val trace = a00 + a11
        val discriminant = sqrt(max(0.0, (a00 - a11) * (a00 - a11) + 4.0 * a01 * a01))
        val eigenMax = 0.5 * (trace + discriminant)
        val eigenMin = 0.5 * (trace - discriminant)
        if (eigenMin <= MINIMUM_EIGENVALUE || !eigenMin.isFinite() || !eigenMax.isFinite()) return null

        val conditionNumber = eigenMax / eigenMin
        if (!conditionNumber.isFinite()) return null

        return NormalEquations(a00, a01, a11, gradient0, gradient1, conditionNumber, residualArcSeconds)
    }

    private fun differentiate(
        determination: PolarErrorDetermination,
        observed: TopocentricCoordinates,
        observationTime: Instant,
        azimuthErrorDeg: Double,
        altitudeErrorDeg: Double,
        azimuthDerivative: Boolean
    ): Pair<Double, Double>? {
        val positive = if (azimuthDerivative) {
            residual(determination, observed, observationTime, azimuthErrorDeg + DERIVATIVE_STEP_DEG, altitudeErrorDeg)
        } else {
            residual(determination, observed, observationTime, azimuthErrorDeg, altitudeErrorDeg + DERIVATIVE_STEP_DEG)
        } ?: return null

        val negative = if (azimuthDerivative) {
            residual(determination, observed, observationTime, azimuthErrorDeg - DERIVATIVE_STEP_DEG, altitudeErrorDeg)
        } else {
            residual(determination, observed, observationTime, azimuthErrorDeg, altitudeErrorDeg - DERIVATIVE_STEP_DEG)
        } ?: return null

        val scale = 1.0 / (2.0 * DERIVATIVE_STEP_DEG)
        return Pair(
            (positive.first - negative.first) * scale,
            (positive.second - negative.second) * scale
        )
    }

    /**
     * Difference between prediction and observation in a local small angle metric. Azimuth is scaled
     * by the cosine of the mean altitude so both components measure real angular displacement.
     */
    private fun residual(
        determination: PolarErrorDetermination,
        observed: TopocentricCoordinates,
        observationTime: Instant,
        azimuthErrorDeg: Double,
        altitudeErrorDeg: Double
    ): Pair<Double, Double>? {
        val reference = determination.initialReferenceFrame
        val predicted = topocentricForResidual(
            EquatorialCoordinates(reference.raDeg, reference.decDeg),
            determination.initial.azimuthErrorDeg,
            determination.initial.altitudeErrorDeg,
            azimuthErrorDeg,
            altitudeErrorDeg,
            determination.site,
            determination.refraction,
            observationTime
        )

        val meanAltitude = 0.5 * (predicted.altitudeDeg + observed.altitudeDeg).toRad()
        val residualAzimuth =
            normalizeSigned180(predicted.azimuthDeg - observed.azimuthDeg) * cos(meanAltitude)
        val residualAltitude = predicted.altitudeDeg - observed.altitudeDeg
        if (!residualAzimuth.isFinite() || !residualAltitude.isFinite()) return null
        return Pair(residualAzimuth, residualAltitude)
    }

    private fun solve2x2(
        a00: Double,
        a01: Double,
        a11: Double,
        b0: Double,
        b1: Double
    ): Pair<Double, Double>? {
        val determinant = a00 * a11 - a01 * a01
        if (abs(determinant) <= MINIMUM_EIGENVALUE || !determinant.isFinite()) return null
        val x0 = (a11 * b0 - a01 * b1) / determinant
        val x1 = (a00 * b1 - a01 * b0) / determinant
        if (!x0.isFinite() || !x1.isFinite()) return null
        return Pair(x0, x1)
    }
}
