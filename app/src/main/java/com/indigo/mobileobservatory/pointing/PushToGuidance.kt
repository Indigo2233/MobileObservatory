package com.indigo.mobileobservatory.pointing

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

enum class GuidanceProximity {
    FAR,
    MEDIUM,
    NEAR,
    ON_TARGET
}

/**
 * Alt/Az push-to guidance for Dobsonian (and any alt-az) mounts.
 * Pure math — no Android deps. Fake or real attitude both feed the same API.
 */
data class GuidanceCommand(
    val deltaAltDeg: Double,
    /** Shortest-path azimuth delta in (−180, 180]. Positive = turn right (increase az). */
    val deltaAzDeg: Double,
    val separationDeg: Double,
    val proximity: GuidanceProximity,
    val zenithDegenerate: Boolean,
    /** Fraction of eyepiece FOV remaining (1 = one FOV away). */
    val eyepieceFovFraction: Double
)

object PushToGuidance {
    const val FAR_THRESHOLD_DEG = 10.0
    const val MEDIUM_THRESHOLD_DEG = 1.0
    /** Above this altitude, azimuth arrows become unreliable. */
    const val ZENITH_DEGENERATE_ALT_DEG = 80.0

    fun normalizeAzimuth(azDeg: Double): Double {
        var a = azDeg % 360.0
        if (a < 0.0) a += 360.0
        return a
    }

    /** Shortest signed delta from [fromAz] to [toAz], result in (−180, 180]. */
    fun shortestAzimuthDelta(fromAz: Double, toAz: Double): Double {
        var d = normalizeAzimuth(toAz) - normalizeAzimuth(fromAz)
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    fun separationDeg(deltaAlt: Double, deltaAz: Double): Double =
        hypot(deltaAlt, deltaAz)

    /**
     * Compute guidance from current telescope pointing to target (both alt/az degrees).
     * [previousProximity] enables hysteresis so status does not flicker at thresholds.
     */
    fun compute(
        currentAltDeg: Double,
        currentAzDeg: Double,
        targetAltDeg: Double,
        targetAzDeg: Double,
        eyepieceFovDeg: Double = 1.5,
        previousProximity: GuidanceProximity? = null
    ): GuidanceCommand {
        val fov = eyepieceFovDeg.coerceAtLeast(0.1)
        val dAlt = targetAltDeg - currentAltDeg
        val dAz = shortestAzimuthDelta(currentAzDeg, targetAzDeg)
        val sep = separationDeg(dAlt, dAz)
        val zenith = currentAltDeg >= ZENITH_DEGENERATE_ALT_DEG ||
            targetAltDeg >= ZENITH_DEGENERATE_ALT_DEG
        val proximity = classifyProximity(sep, fov, previousProximity)
        return GuidanceCommand(
            deltaAltDeg = dAlt,
            deltaAzDeg = dAz,
            separationDeg = sep,
            proximity = proximity,
            zenithDegenerate = zenith,
            eyepieceFovFraction = sep / fov
        )
    }

    fun classifyProximity(
        separationDeg: Double,
        eyepieceFovDeg: Double,
        previous: GuidanceProximity?
    ): GuidanceProximity {
        val fov = eyepieceFovDeg.coerceAtLeast(0.1)
        val enterOnTarget = fov * 0.25
        val leaveOnTarget = fov * 0.45
        val enterNear = fov.coerceAtMost(MEDIUM_THRESHOLD_DEG)
        val leaveNear = (fov * 1.2).coerceAtMost(MEDIUM_THRESHOLD_DEG * 1.2)

        if (previous == GuidanceProximity.ON_TARGET) {
            if (separationDeg <= leaveOnTarget) return GuidanceProximity.ON_TARGET
        } else if (separationDeg <= enterOnTarget) {
            return GuidanceProximity.ON_TARGET
        }

        if (previous == GuidanceProximity.NEAR) {
            if (separationDeg <= leaveNear) return GuidanceProximity.NEAR
        } else if (separationDeg <= enterNear) {
            return GuidanceProximity.NEAR
        }

        if (previous == GuidanceProximity.MEDIUM) {
            if (separationDeg < FAR_THRESHOLD_DEG * 1.15) return GuidanceProximity.MEDIUM
        } else if (separationDeg < FAR_THRESHOLD_DEG) {
            return GuidanceProximity.MEDIUM
        }

        return GuidanceProximity.FAR
    }

    /** Human-readable move hint for mock / TTS (English tokens; UI localizes). */
    fun moveTokens(cmd: GuidanceCommand): List<String> {
        if (cmd.proximity == GuidanceProximity.ON_TARGET) return listOf("on_target")
        if (cmd.zenithDegenerate) {
            return buildList {
                if (abs(cmd.deltaAltDeg) > 0.05) {
                    add(if (cmd.deltaAltDeg > 0) "lower_first" else "raise_then_turn")
                }
                add("az")
            }
        }
        return buildList {
            if (abs(cmd.deltaAltDeg) >= 0.05) {
                add(if (cmd.deltaAltDeg > 0) "up" else "down")
            }
            if (abs(cmd.deltaAzDeg) >= 0.05) {
                add(if (cmd.deltaAzDeg > 0) "right" else "left")
            }
        }
    }

    fun formatDegrees(value: Double): String {
        val a = abs(value)
        return when {
            a >= 10.0 -> "%.0f°".format(a)
            a >= 1.0 -> "%.1f°".format(a)
            else -> "%.0f′".format(a * 60.0)
        }
    }

    fun signedHint(delta: Double, positive: String, negative: String): String {
        if (abs(delta) < 0.05) return ""
        val dir = if (delta.sign >= 0) positive else negative
        return "$dir ${formatDegrees(delta)}"
    }
}
