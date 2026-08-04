package com.indigo.mobileobservatory.astro

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val DEG_TO_RAD = PI / 180.0
const val RAD_TO_DEG = 180.0 / PI
const val ARCSEC_TO_RAD = DEG_TO_RAD / 3600.0

fun Double.toRad(): Double = this * DEG_TO_RAD
fun Double.toDeg(): Double = this * RAD_TO_DEG

fun normalize360(degrees: Double): Double {
    val result = degrees % 360.0
    return if (result < 0.0) result + 360.0 else result
}

fun normalizeSigned180(degrees: Double): Double {
    val result = normalize360(degrees)
    return if (result > 180.0) result - 360.0 else result
}

fun normalizeRadians(radians: Double): Double {
    val twoPi = 2.0 * PI
    val result = radians % twoPi
    return if (result < 0.0) result + twoPi else result
}

/** Cartesian direction in a right-handed equatorial frame (x towards the equinox, z towards the pole). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    val length: Double get() = sqrt(x * x + y * y + z * z)

    fun unit(): Vec3 {
        val len = length
        return if (len == 0.0) this else Vec3(x / len, y / len, z / len)
    }

    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Double) = Vec3(x * scalar, y * scalar, z * scalar)

    companion object {
        /** Spherical to Cartesian using right ascension / declination style angles in radians. */
        fun ofSpherical(lonRad: Double, latRad: Double): Vec3 {
            val cosLat = cos(latRad)
            return Vec3(cosLat * cos(lonRad), cosLat * sin(lonRad), sin(latRad))
        }
    }

    /** Longitude of the direction in radians, normalized to [0, 2pi). */
    fun longitudeRad(): Double = normalizeRadians(atan2(y, x))

    /** Latitude of the direction in radians. */
    fun latitudeRad(): Double = atan2(z, sqrt(x * x + y * y))
}

/** Row-major 3x3 rotation matrix. */
class Mat3(private val m: DoubleArray) {
    init {
        require(m.size == 9) { "A 3x3 matrix needs exactly 9 elements." }
    }

    operator fun times(v: Vec3): Vec3 = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z
    )

    operator fun times(other: Mat3): Mat3 {
        val o = other.m
        val r = DoubleArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                r[row * 3 + col] =
                    m[row * 3] * o[col] + m[row * 3 + 1] * o[3 + col] + m[row * 3 + 2] * o[6 + col]
            }
        }
        return Mat3(r)
    }

    /** Rotation matrices are orthogonal, so the transpose is the inverse. */
    fun transpose(): Mat3 = Mat3(
        doubleArrayOf(
            m[0], m[3], m[6],
            m[1], m[4], m[7],
            m[2], m[5], m[8]
        )
    )

    companion object {
        fun identity() = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** Right-handed rotation of a vector about the x axis. */
        fun rotateX(angleRad: Double): Mat3 {
            val c = cos(angleRad)
            val s = sin(angleRad)
            return Mat3(
                doubleArrayOf(
                    1.0, 0.0, 0.0,
                    0.0, c, -s,
                    0.0, s, c
                )
            )
        }

        /** Right-handed rotation of a vector about the z axis. */
        fun rotateZ(angleRad: Double): Mat3 {
            val c = cos(angleRad)
            val s = sin(angleRad)
            return Mat3(
                doubleArrayOf(
                    c, -s, 0.0,
                    s, c, 0.0,
                    0.0, 0.0, 1.0
                )
            )
        }
    }
}
