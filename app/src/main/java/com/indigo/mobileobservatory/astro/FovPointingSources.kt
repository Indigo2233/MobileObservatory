package com.indigo.mobileobservatory.astro

import com.indigo.mobileobservatory.pointing.PushToGuidance

enum class FovPointingKind { DIRECT_OPTICAL, CALIBRATED_OPTICAL, PROPAGATED, MOUNT_MODEL }
enum class FovPointingRole { OPTICAL, CALIBRATED, ESTIMATED, PLANNING }
enum class FovPointingReason { READY, NO_SOURCE, STALE, UNKNOWN_ERROR, WRONG_PATH, UNCALIBRATED, CONFLICT, ROLL_UNKNOWN }

data class FovPointingCandidate(
    val opticalPathId: String,
    val anchor: FovSkyAnchor,
    val sourceId: String,
    val kind: FovPointingKind,
    val absoluteUtcMs: Long,
    val estimateUtcMs: Long,
    val uncertaintyDeg: Double?,
    val axisValid: Boolean,
    val calibrationVersion: String? = null,
    val observationId: String = "",
    val positionAngleDeg: Double? = null
)

data class FovPointingDecision(
    val source: FovPointingCandidate? = null,
    val role: FovPointingRole = FovPointingRole.PLANNING,
    val reason: FovPointingReason = FovPointingReason.NO_SOURCE
) {
    val currentAnchor: FovSkyAnchor? get() = source?.anchor
    val showCurrent: Boolean get() = source != null && role != FovPointingRole.PLANNING
}

/** Choose an actual optical path by validity/error/age, never by a fixed device tier. */
object FovPointingSources {
    fun select(opticalPathId: String, candidates: List<FovPointingCandidate>, nowUtcMs: Long,
        requireRoll: Boolean = false, maxUncertaintyDeg: Double = Double.POSITIVE_INFINITY,
        maxAbsoluteAgeMs: Long = 30_000, maxEstimateAgeMs: Long = 3_000): FovPointingDecision {
        if (candidates.isEmpty()) return FovPointingDecision()
        var reason = FovPointingReason.NO_SOURCE
        val usable = candidates.filter { candidate ->
            val problem = when {
                candidate.kind == FovPointingKind.MOUNT_MODEL -> FovPointingReason.NO_SOURCE
                candidate.absoluteUtcMs <= 0 || candidate.estimateUtcMs <= 0 -> FovPointingReason.NO_SOURCE
                candidate.opticalPathId != opticalPathId -> FovPointingReason.WRONG_PATH
                !candidate.axisValid || candidate.kind != FovPointingKind.DIRECT_OPTICAL && candidate.calibrationVersion.isNullOrBlank() -> FovPointingReason.UNCALIBRATED
                nowUtcMs - candidate.absoluteUtcMs !in 0..maxAbsoluteAgeMs ||
                    nowUtcMs - candidate.estimateUtcMs !in 0..maxEstimateAgeMs -> FovPointingReason.STALE
                candidate.uncertaintyDeg == null || !candidate.uncertaintyDeg.isFinite() ||
                    candidate.uncertaintyDeg < 0 || candidate.uncertaintyDeg > maxUncertaintyDeg -> FovPointingReason.UNKNOWN_ERROR
                !candidate.anchor.raHours.isFinite() || !candidate.anchor.decDegrees.isFinite() ||
                    candidate.anchor.decDegrees !in -90.0..90.0 || candidate.anchor.frame.uppercase() !in setOf("JNOW", "J2000", "ICRF") -> FovPointingReason.NO_SOURCE
                requireRoll && (candidate.positionAngleDeg == null || !candidate.positionAngleDeg.isFinite()) -> FovPointingReason.ROLL_UNKNOWN
                else -> null
            }
            if (problem != null) reason = problem
            problem == null
        }
        if (usable.isEmpty()) return FovPointingDecision(reason = reason)
        // Different epochs cannot be compared without a conversion. Refuse ambiguous source switching.
        for (a in usable.indices) for (b in a + 1 until usable.size) {
            val left = usable[a]; val right = usable[b]
            if (left.observationId.isNotBlank() && left.observationId == right.observationId) continue
            val leftFrame = left.anchor.jsFrame(); val rightFrame = right.anchor.jsFrame()
            if (leftFrame != rightFrame || PushToGuidance.separationDeg(left.anchor.decDegrees, left.anchor.raHours * 15,
                    right.anchor.decDegrees, right.anchor.raHours * 15) > left.uncertaintyDeg!! + right.uncertaintyDeg!! + .02) {
                return FovPointingDecision(reason = FovPointingReason.CONFLICT)
            }
        }
        val best = usable.minWith(compareBy<FovPointingCandidate> { it.uncertaintyDeg!! }
            .thenByDescending { it.absoluteUtcMs }.thenByDescending { it.estimateUtcMs }.thenBy { it.sourceId })
        val role = when (best.kind) {
            FovPointingKind.DIRECT_OPTICAL -> FovPointingRole.OPTICAL
            FovPointingKind.CALIBRATED_OPTICAL -> FovPointingRole.CALIBRATED
            FovPointingKind.PROPAGATED -> FovPointingRole.ESTIMATED
            FovPointingKind.MOUNT_MODEL -> FovPointingRole.PLANNING
        }
        return FovPointingDecision(best, role, FovPointingReason.READY)
    }
}
