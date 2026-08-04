package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.Refraction
import com.indigo.mobileobservatory.astro.RefractionParameters
import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import com.indigo.mobileobservatory.astro.normalizeSigned180
import java.time.Instant
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A plate solved field centre in J2000 equatorial coordinates together with its observation time. */
data class PolarSolvePoint(
    val raDeg: Double,
    val decDeg: Double,
    val solvedAt: Instant
)

/** A measurement expressed both as observed horizontal coordinates and as a unit direction. */
data class PolarPosition(
    val topocentric: TopocentricCoordinates,
    val vector: PolarVector3
) {
    companion object {
        fun of(
            point: PolarSolvePoint,
            site: ObserverSite,
            refraction: RefractionParameters
        ): PolarPosition {
            val topocentric = CoordinateTransform.j2000ToTopocentric(
                EquatorialCoordinates(point.raDeg, point.decDeg),
                point.solvedAt,
                site,
                refraction
            )
            return PolarPosition(topocentric, PolarVector3.fromTopocentric(topocentric))
        }

        fun of(vector: PolarVector3): PolarPosition = PolarPosition(vector.toTopocentric(), vector)
    }
}

data class PolarAlignmentResult(
    val axisAzimuthDeg: Double,
    val axisAltitudeDeg: Double,
    val altitudeErrorDeg: Double,
    val azimuthErrorDeg: Double,
    val totalErrorDeg: Double
) {
    val altitudeErrorArcMin: Double get() = altitudeErrorDeg * 60.0
    val azimuthErrorArcMin: Double get() = azimuthErrorDeg * 60.0
    val totalErrorArcMin: Double get() = totalErrorDeg * 60.0
}

/** Where the field used for live correction currently sits, and whether it is a degenerate choice. */
data class CorrectionFieldInfo(
    val azimuthDeg: Double,
    val altitudeDeg: Double,
    val distanceToEastWestDeg: Double
) {
    val nearEastWest: Boolean get() = distanceToEastWestDeg <= EAST_WEST_WARNING_THRESHOLD_DEG

    companion object {
        const val EAST_WEST_WARNING_THRESHOLD_DEG = 5.0
    }
}

/**
 * Reconstructs the mount's polar axis from three plate solves taken while rotating the right
 * ascension axis, and expresses the misalignment as altitude and azimuth errors.
 *
 * This is a port of the `PolarErrorDetermination` class from NINA's Three Point Polar Alignment
 * plugin, including its refracted-pole handling and its degeneracy warnings.
 */
class PolarErrorDetermination(
    val initialReferenceFrame: PolarSolvePoint,
    val firstPosition: PolarPosition,
    val secondPosition: PolarPosition,
    val thirdPosition: PolarPosition,
    val site: ObserverSite,
    val refraction: RefractionParameters,
    val correctForRefraction: Boolean,
    val declinationSpreadArcsec: Double = 0.0
) {
    val northern: Boolean = site.latitudeDeg > 0.0

    val axisPosition: PolarPosition
    val initial: PolarAlignmentResult

    init {
        var planeVector = PolarVector3.determinePlaneVector(
            firstPosition.vector,
            secondPosition.vector,
            thirdPosition.vector
        )
        if ((northern && planeVector.x < 0.0) || (!northern && planeVector.x > 0.0)) {
            planeVector = -planeVector
        }
        axisPosition = PolarPosition.of(planeVector)
        initial = errorFor(axisPosition.topocentric)
    }

    /** Altitude of the pole the measured axis is compared against. */
    val poleAltitudeDeg: Double
        get() {
            val geometric = abs(site.latitudeDeg)
            if (correctForRefraction) return geometric
            val refracted = Refraction.refractedAltitudeDeg(geometric, refraction)
            return if (refracted.isFinite()) refracted else geometric
        }

    /** Converts an arbitrary mount axis direction into altitude/azimuth error components. */
    fun errorFor(axis: TopocentricCoordinates): PolarAlignmentResult {
        val pole = poleAltitudeDeg
        val altitudeError = if (northern) {
            axis.altitudeDeg - pole
        } else {
            pole - axis.altitudeDeg
        }
        val azimuthError = if (northern) {
            normalizeSigned180(axis.azimuthDeg)
        } else {
            normalizeSigned180(axis.azimuthDeg + 180.0)
        }
        return PolarAlignmentResult(
            axisAzimuthDeg = axis.azimuthDeg,
            axisAltitudeDeg = axis.altitudeDeg,
            altitudeErrorDeg = altitudeError,
            azimuthErrorDeg = azimuthError,
            totalErrorDeg = hypot(altitudeError, azimuthError)
        )
    }

    /**
     * Rebuilds a result from residual error components so the live correction phase reports the
     * same fields as the initial solve.
     */
    fun resultForResidual(azimuthErrorDeg: Double, altitudeErrorDeg: Double): PolarAlignmentResult {
        val pole = poleAltitudeDeg
        val axisAltitude = if (northern) pole + altitudeErrorDeg else pole - altitudeErrorDeg
        val axisAzimuth = if (northern) azimuthErrorDeg else azimuthErrorDeg - 180.0
        return PolarAlignmentResult(
            axisAzimuthDeg = com.indigo.mobileobservatory.astro.normalize360(axisAzimuth),
            axisAltitudeDeg = axisAltitude,
            altitudeErrorDeg = altitudeErrorDeg,
            azimuthErrorDeg = azimuthErrorDeg,
            totalErrorDeg = hypot(altitudeErrorDeg, azimuthErrorDeg)
        )
    }

    /** Three points too close in declination make the plane fit ill conditioned. */
    val declinationSpreadLarge: Boolean get() = declinationSpreadArcsec > 2.0

    val initialErrorLarge: Boolean
        get() = initial.totalErrorDeg > 2.0 && initial.totalErrorDeg <= 10.0

    val initialErrorHuge: Boolean get() = initial.totalErrorDeg > 10.0

    /**
     * Position of the field used during the correction phase. Close to due east or west the azimuth
     * adjustment barely moves the stars, so the live estimate becomes unreliable there.
     */
    fun correctionFieldInfo(frame: PolarSolvePoint): CorrectionFieldInfo {
        val topocentric = CoordinateTransform.j2000ToTopocentric(
            EquatorialCoordinates(frame.raDeg, frame.decDeg),
            frame.solvedAt,
            site,
            refraction
        )
        val distanceToEast = abs(normalizeSigned180(topocentric.azimuthDeg - 90.0))
        val distanceToWest = abs(normalizeSigned180(topocentric.azimuthDeg - 270.0))
        return CorrectionFieldInfo(
            azimuthDeg = topocentric.azimuthDeg,
            altitudeDeg = topocentric.altitudeDeg,
            distanceToEastWestDeg = min(distanceToEast, distanceToWest)
        )
    }

    /**
     * Where the reference field would end up after turning the azimuth knob by [azimuthAngleDeg]
     * and the altitude knob by [altitudeAngleDeg]. Used to draw the correction target.
     */
    fun destinationCoordinates(
        azimuthAngleDeg: Double,
        altitudeAngleDeg: Double,
        observationTime: Instant
    ): TopocentricCoordinates {
        val referenceTopocentric = CoordinateTransform.j2000ToTopocentric(
            EquatorialCoordinates(initialReferenceFrame.raDeg, initialReferenceFrame.decDeg),
            observationTime,
            site,
            null
        )
        val referenceVector = PolarVector3.fromTopocentric(referenceTopocentric)

        val azimuthDestination =
            PolarVector3.rotateByRodrigues(referenceVector, ZENITH_AXIS, azimuthAngleDeg)
        val altitudeAxis = PolarVector3.rotateByRodrigues(WEST_AXIS, ZENITH_AXIS, azimuthAngleDeg)
        return PolarVector3.rotateByRodrigues(azimuthDestination, altitudeAxis, altitudeAngleDeg)
            .toTopocentric()
    }

    companion object {
        internal val ZENITH_AXIS = PolarVector3(0.0, 0.0, 1.0)
        internal val WEST_AXIS = PolarVector3(0.0, 1.0, 0.0)
    }
}

object PolarAlignmentCalculator {
    /** Declination spread of the three measurement points, in arcseconds. */
    fun declinationSpreadArcsec(decDegrees: List<Double>): Double {
        if (decDegrees.size < 3) return 0.0
        val minimum = decDegrees.reduce { a, b -> min(a, b) }
        val maximum = decDegrees.reduce { a, b -> max(a, b) }
        return (maximum - minimum) * 3600.0
    }

    fun determine(
        points: List<PolarSolvePoint>,
        site: ObserverSite,
        refraction: RefractionParameters = RefractionParameters.STANDARD,
        correctForRefraction: Boolean = true,
        declinationSpreadArcsec: Double = 0.0,
        referenceFrame: PolarSolvePoint = points.last()
    ): PolarErrorDetermination {
        require(points.size == 3) { "Three solved points are required." }
        return PolarErrorDetermination(
            initialReferenceFrame = referenceFrame,
            firstPosition = PolarPosition.of(points[0], site, refraction),
            secondPosition = PolarPosition.of(points[1], site, refraction),
            thirdPosition = PolarPosition.of(points[2], site, refraction),
            site = site,
            refraction = refraction,
            correctForRefraction = correctForRefraction,
            declinationSpreadArcsec = declinationSpreadArcsec
        )
    }
}
