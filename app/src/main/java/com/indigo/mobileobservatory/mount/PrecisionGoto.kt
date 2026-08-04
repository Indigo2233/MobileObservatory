package com.indigo.mobileobservatory.mount

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

enum class PrecisionGotoPhase {
    IDLE,
    SLEWING,
    SETTLING,
    CAPTURING,
    SOLVING,
    SYNCING,
    CORRECTING,
    SUCCEEDED,
    FAILED
}

data class PrecisionGotoProgress(
    val phase: PrecisionGotoPhase = PrecisionGotoPhase.IDLE,
    val targetName: String = "",
    val iteration: Int = 0,
    val maxIterations: Int = PrecisionGotoMath.MAX_ITERATIONS,
    val errorArcmin: Double? = null,
    val solvedRaHours: Double? = null,
    val solvedDecDeg: Double? = null,
    val message: String = ""
) {
    val isActive: Boolean
        get() = phase != PrecisionGotoPhase.IDLE &&
            phase != PrecisionGotoPhase.SUCCEEDED &&
            phase != PrecisionGotoPhase.FAILED
}

object PrecisionGotoMath {
    const val TOLERANCE_ARCMIN = 2.0
    const val MAX_ITERATIONS = 5

    fun degreesToArcmin(degrees: Double): Double = degrees * 60.0

    fun raDistanceDeg(a: Double, b: Double): Double {
        return 180.0 - kotlin.math.abs(kotlin.math.abs(a - b) - 180.0)
    }

    fun angularSeparationDeg(first: MountCoordinates, second: MountCoordinates): Double {
        val firstDec = Math.toRadians(first.decDeg)
        val secondDec = Math.toRadians(second.decDeg)
        val raDelta = Math.toRadians(raDistanceDeg(first.raDeg, second.raDeg))
        val cosine = sin(firstDec) * sin(secondDec) +
            cos(firstDec) * cos(secondDec) * cos(raDelta)
        return Math.toDegrees(acos(cosine.coerceIn(-1.0, 1.0)))
    }

    /**
     * Without mount sync: command a corrective GOTO so the sky moves by (target - solved).
     * [mount] is the mount-reported position while physically pointing at [solved].
     */
    fun correctiveCommand(
        mount: MountCoordinates,
        target: MountCoordinates,
        solved: MountCoordinates
    ): MountCoordinates {
        var raHours = mount.raHours + (target.raHours - solved.raHours)
        while (raHours < 0.0) raHours += 24.0
        while (raHours >= 24.0) raHours -= 24.0
        val decDeg = (mount.decDeg + (target.decDeg - solved.decDeg)).coerceIn(-90.0, 90.0)
        return MountCoordinates(raHours = raHours, decDeg = decDeg)
    }
}
