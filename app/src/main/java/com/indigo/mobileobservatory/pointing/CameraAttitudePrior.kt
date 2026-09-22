package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Rough lost-in-space prior from the handset site, rotation vector, and camera image-up.
 *
 * The optical axis becomes a J2000 search centre. Image-up, after conversion through the same
 * apparent-place chain, becomes the expected field rotation used by [BlindWideFieldMatcher]
 * (`atan2(y·east, y·north)`). Magnetometer heading is treated as a hint, not a truth value.
 */
internal data class AttitudeSolvePrior(
    val centerRaDeg: Double,
    val centerDecDeg: Double,
    val rotationDeg: Double? = null,
    val centerUncertaintyDeg: Double = DEFAULT_CENTER_UNCERTAINTY_DEG,
    val rotationUncertaintyDeg: Double = DEFAULT_ROTATION_UNCERTAINTY_DEG
) {
    val center: EquatorialCoordinates get() = EquatorialCoordinates(centerRaDeg, centerDecDeg)

    companion object {
        const val DEFAULT_CENTER_UNCERTAINTY_DEG = 25.0
        const val DEFAULT_ROTATION_UNCERTAINTY_DEG = 40.0
    }
}

internal data class CameraImageAxes(
    val opticalAxis: Direction3,
    val imageRight: Direction3,
    val imageUp: Direction3
)

internal object CameraAttitudePrior {
    /**
     * Rear-camera image axes from Android's device-to-ENU rotation matrix.
     *
     * The buffer used by star extraction is sensor-native (not display-rotated).
     * `SENSOR_ORIENTATION` is the clockwise rotation that would make that buffer upright
     * in the device's natural portrait orientation; the same angle maps device +X/+Y onto
     * image +X (right) / +Y (down). Matcher +Y is image-up, i.e. the opposite of image-down.
     */
    fun imageAxesEnu(rotationMatrix: FloatArray, sensorOrientationDeg: Int): CameraImageAxes {
        require(rotationMatrix.size >= 9) { "Rotation matrix must contain 9 elements" }
        val deviceX = column(rotationMatrix, 0)
        val deviceY = column(rotationMatrix, 1)
        val deviceZ = column(rotationMatrix, 2)
        val theta = Math.toRadians(sensorOrientationDeg.toDouble())
        val cos = cos(theta)
        val sin = sin(theta)
        val opticalAxis = (deviceZ * -1.0).unit()
        val imageRight = (deviceX * cos + deviceY * sin).unit()
        val imageUp = opticalAxis.cross(imageRight).unit()
        return CameraImageAxes(opticalAxis, imageRight, imageUp)
    }

    fun fromOpticalAxis(
        opticalAxisEnu: Direction3,
        imageUpEnu: Direction3?,
        instant: Instant,
        site: ObserverSite
    ): AttitudeSolvePrior {
        val look = opticalAxisEnu.unit()
        val (altitudeDeg, azimuthDeg) = look.toAltAz()
        val center = CoordinateTransform.topocentricToJ2000(
            TopocentricCoordinates(altitudeDeg, azimuthDeg), instant, site, refraction = null
        )
        val rotationDeg = imageUpEnu?.let { rawUp ->
            val parallel = look.dot(rawUp.unit())
            if (abs(parallel) > 0.995) return@let null
            val imageUp = (rawUp.unit() - look * parallel).unit()
            fieldRotationDeg(center, imageUp, instant, site)
        }
        return AttitudeSolvePrior(center.raDeg, center.decDeg, rotationDeg)
    }

    fun fromRotationMatrix(
        rotationMatrix: FloatArray,
        sensorOrientationDeg: Int,
        instant: Instant,
        site: ObserverSite
    ): AttitudeSolvePrior {
        val axes = imageAxesEnu(rotationMatrix, sensorOrientationDeg)
        return fromOpticalAxis(axes.opticalAxis, axes.imageUp, instant, site)
    }

    internal fun fieldRotationDeg(
        center: EquatorialCoordinates,
        imageUpEnu: Direction3,
        instant: Instant,
        site: ObserverSite
    ): Double {
        val (altitudeDeg, azimuthDeg) = imageUpEnu.unit().toAltAz()
        val upEquatorial = CoordinateTransform.topocentricToJ2000(
            TopocentricCoordinates(altitudeDeg, azimuthDeg), instant, site, refraction = null
        )
        val centerVec = equatorial(center)
        val yAxis = equatorial(upEquatorial)
        val east = Vec3(-sin(centerVec.raRad), cos(centerVec.raRad), 0.0)
        val north = Vec3(
            -cos(centerVec.raRad) * sin(Math.toRadians(center.decDeg)),
            -sin(centerVec.raRad) * sin(Math.toRadians(center.decDeg)),
            cos(Math.toRadians(center.decDeg))
        )
        return Math.toDegrees(atan2(yAxis.dot(east), yAxis.dot(north)))
            .let { ((it % 360.0) + 360.0) % 360.0 }
    }

    private fun column(matrix: FloatArray, index: Int) = Direction3(
        east = matrix[index].toDouble(),
        north = matrix[index + 3].toDouble(),
        up = matrix[index + 6].toDouble()
    )

    private fun equatorial(coordinates: EquatorialCoordinates): Vec3 {
        val ra = Math.toRadians(coordinates.raDeg)
        val dec = Math.toRadians(coordinates.decDeg)
        return Vec3(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
    }

    private data class Vec3(val x: Double, val y: Double, val z: Double) {
        val raRad get() = atan2(y, x)
        fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    }
}

internal fun shortestRotationDeltaDeg(a: Double, b: Double): Double {
    val delta = ((a - b) % 360.0 + 540.0) % 360.0 - 180.0
    return abs(delta)
}

internal fun diagonalFovDeg(widthDeg: Double, heightDeg: Double): Double =
    Math.toDegrees(
        2.0 * atan2(
            hypot(tan(Math.toRadians(widthDeg / 2.0)), tan(Math.toRadians(heightDeg / 2.0))),
            1.0
        )
    )
