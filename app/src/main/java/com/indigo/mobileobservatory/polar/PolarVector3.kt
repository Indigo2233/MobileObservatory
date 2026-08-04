package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import com.indigo.mobileobservatory.astro.normalize360
import com.indigo.mobileobservatory.astro.toDeg
import com.indigo.mobileobservatory.astro.toRad
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direction in the local horizontal frame, using the same axis convention as NINA's TPPA plugin:
 * x towards north, y towards west and z towards the zenith.
 */
data class PolarVector3(val x: Double, val y: Double, val z: Double) {
    val length: Double get() = sqrt(x * x + y * y + z * z)

    fun unit(): PolarVector3 {
        val len = length
        return if (len == 0.0) PolarVector3(0.0, 0.0, 0.0) else PolarVector3(x / len, y / len, z / len)
    }

    fun toTopocentric(): TopocentricCoordinates {
        val horizontal = sqrt(x * x + y * y)
        if (horizontal == 0.0 && z == 0.0) return TopocentricCoordinates(0.0, 0.0)
        return TopocentricCoordinates(
            altitudeDeg = atan2(z, horizontal).toDeg(),
            azimuthDeg = normalize360(atan2(-y, x).toDeg())
        )
    }

    operator fun plus(other: PolarVector3) = PolarVector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: PolarVector3) = PolarVector3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Double) = PolarVector3(x * scalar, y * scalar, z * scalar)
    operator fun unaryMinus() = PolarVector3(-x, -y, -z)

    companion object {
        fun fromTopocentric(coordinates: TopocentricCoordinates): PolarVector3 {
            val theta = -coordinates.azimuthDeg.toRad()
            val phi = Math.PI / 2.0 - coordinates.altitudeDeg.toRad()
            return PolarVector3(
                x = cos(theta) * sin(phi),
                y = sin(theta) * sin(phi),
                z = cos(phi)
            )
        }

        /** Unit normal of the plane through the three directions, i.e. the rotation axis they orbit. */
        fun determinePlaneVector(a: PolarVector3, b: PolarVector3, c: PolarVector3): PolarVector3 =
            crossProduct(b - a, c - b).unit()

        fun crossProduct(v1: PolarVector3, v2: PolarVector3) = PolarVector3(
            v1.y * v2.z - v1.z * v2.y,
            v1.z * v2.x - v1.x * v2.z,
            v1.x * v2.y - v1.y * v2.x
        )

        fun scalarProduct(v1: PolarVector3, v2: PolarVector3): Double =
            v1.x * v2.x + v1.y * v2.y + v1.z * v2.z

        /** Rodrigues' rotation of [v] about the unit axis [axis] by [degrees]. */
        fun rotateByRodrigues(v: PolarVector3, axis: PolarVector3, degrees: Double): PolarVector3 {
            val radians = degrees.toRad()
            return v * cos(radians) +
                crossProduct(axis, v) * sin(radians) +
                axis * scalarProduct(axis, v) * (1.0 - cos(radians))
        }
    }
}
