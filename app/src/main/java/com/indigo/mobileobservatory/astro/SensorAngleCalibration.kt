package com.indigo.mobileobservatory.astro

import kotlin.math.abs

data class SensorAngleCalibration(
    val offsetDeg: Double = 0.0,
    val reversed: Boolean = false
) {
    fun skyPositionAngle(mechanicalAngleDeg: Double): Double =
        normalizeAngle((if (reversed) -mechanicalAngleDeg else mechanicalAngleDeg) + offsetDeg)

    fun mechanicalAngle(skyPositionAngleDeg: Double): Double {
        val relative = normalizeAngle(skyPositionAngleDeg - offsetDeg)
        return normalizeAngle(if (reversed) -relative else relative)
    }

    fun calibrated(solvedSkyPositionAngleDeg: Double, mechanicalAngleDeg: Double): SensorAngleCalibration {
        val signedMechanical = if (reversed) -mechanicalAngleDeg else mechanicalAngleDeg
        return copy(offsetDeg = normalizeAngle(solvedSkyPositionAngleDeg - signedMechanical))
    }

    companion object {
        const val OFFSET_PREF = "star_map_rotator_position_angle_offset"
        const val REVERSED_PREF = "star_map_rotator_position_angle_reversed"

        fun normalizeAngle(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

        fun angularError(firstDeg: Double, secondDeg: Double): Double {
            val delta = abs(normalizeAngle(firstDeg) - normalizeAngle(secondDeg))
            return minOf(delta, 360.0 - delta)
        }
    }
}
