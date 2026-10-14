package com.indigo.mobileobservatory.pointing

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Angles for the actual working optical axis, in the same topocentric frame as the target. */
data class VerificationField(
    val circleDiameterDeg: Double? = null,
    val widthDeg: Double? = null,
    val heightDeg: Double? = null,
    /** Camera roll relative to the local altitude-up tangent, not a celestial WCS PA. */
    val altitudeUpRollDeg: Double? = null
) {
    init {
        require(circleDiameterDeg == null || circleDiameterDeg.isFinite() && circleDiameterDeg > 0 && circleDiameterDeg < 180)
        require(widthDeg == null || widthDeg.isFinite() && widthDeg > 0 && widthDeg < 180)
        require(heightDeg == null || heightDeg.isFinite() && heightDeg > 0 && heightDeg < 180)
        require(altitudeUpRollDeg == null || altitudeUpRollDeg.isFinite())
    }

    fun contains(axisAlt: Double, axisAz: Double, targetAlt: Double, targetAz: Double,
        uncertaintyDeg: Double, alreadyVerified: Boolean = false): Boolean {
        if (!uncertaintyDeg.isFinite() || uncertaintyDeg < 0) return false
        val fraction = if (alreadyVerified) .45 else .25
        circleDiameterDeg?.let { diameter ->
            return PushToGuidance.separationDeg(axisAlt, axisAz, targetAlt, targetAz) + uncertaintyDeg <= diameter * fraction
        }
        val width = widthDeg ?: return false
        val height = heightDeg ?: return false
        val roll = altitudeUpRollDeg ?: return false
        val (right, up) = PushToGuidance.fieldOffsets(axisAlt, axisAz, targetAlt, targetAz) ?: return false
        val angle = Math.toRadians(roll)
        val tangentRight = kotlin.math.tan(Math.toRadians(right))
        val tangentUp = kotlin.math.tan(Math.toRadians(up))
        val x = Math.toDegrees(kotlin.math.atan(tangentRight * cos(angle) - tangentUp * sin(angle)))
        val y = Math.toDegrees(kotlin.math.atan(tangentRight * sin(angle) + tangentUp * cos(angle)))
        return abs(x) + uncertaintyDeg <= width * fraction && abs(y) + uncertaintyDeg <= height * fraction
    }
}

enum class VerificationPhase { NEEDS_REFERENCE, MOVING, SETTLING, VERIFYING, GUIDING, VERIFIED, BACKOFF, RETRY_LIMIT }

data class VerificationTicket(
    val taskGeneration: Long,
    val motionEpoch: Long,
    val motionGeneration: Long,
    val requestedElapsedMs: Long,
    val requestSequence: Long
)

data class OpticalVerificationEvidence(
    val observedElapsedMs: Long,
    val exposureStartElapsedMs: Long,
    val exposureEndElapsedMs: Long,
    val opticalAltDeg: Double,
    val opticalAzDeg: Double,
    val targetAltDegAtObservation: Double,
    val targetAzDegAtObservation: Double,
    /** Includes solve, installation, reference centering, time pairing and flexure bounds. */
    val uncertaintyDeg: Double?,
    val calibrationVersion: String,
    val independentOpticalReference: Boolean,
    val qualityAccepted: Boolean,
    val exposureMotionDeg: Double?,
    val clockUncertaintyMs: Double? = null,
    val calibrationValid: Boolean = true
) {
    val earliestObservedElapsedMs get() = observedElapsedMs - kotlin.math.ceil(clockUncertaintyMs ?: 0.0).toLong()
}

data class PushToVerificationState(
    val phase: VerificationPhase = VerificationPhase.NEEDS_REFERENCE,
    val taskGeneration: Long = 0,
    val motionEpoch: Long = 0,
    val needsReference: Boolean = true,
    val consecutiveFailures: Int = 0,
    val rejectionReason: String = "",
    val verifiedAtElapsedMs: Long? = null
) {
    val isVerified: Boolean get() = phase == VerificationPhase.VERIFIED
}

data class VerificationPolicy(
    val cooldownMs: Long = 1_500,
    val maxAbsoluteAgeMs: Long = 10_000,
    val maxExposureMotionDeg: Double = .02,
    val maxClockUncertaintyMs: Double = 250.0,
    val maxConsecutiveFailures: Int = 5,
    val maxRetryPeriodMs: Long = 60_000,
    val maximumBackoffMs: Long = 15_000,
    val maxCapturePeriodMs: Long = 45_000,
    /** Conservative sky motion bound for an untracked Dob; never stamp an old fix as new. */
    val untrackedSkyMotionDegPerSec: Double = .0042
) {
    init {
        require(cooldownMs >= 0 && maxAbsoluteAgeMs > 0 && maxExposureMotionDeg >= 0 &&
            maxClockUncertaintyMs >= 0 && maxConsecutiveFailures > 0 && maxRetryPeriodMs > 0 &&
            maxCapturePeriodMs > 0 && maximumBackoffMs >= cooldownMs)
        require(untrackedSkyMotionDegPerSec.isFinite() && untrackedSkyMotionDegPerSec >= 0)
    }
}

/** Fresh optical proof belongs to one task, installation and movement. IMU never certifies it. */
class PushToVerificationSession(private val policy: VerificationPolicy = VerificationPolicy()) {
    @Volatile var state = PushToVerificationState()
        private set
    private var taskId = ""
    private var calibrationVersion = ""
    private var motion = MotionPhase.STILL
    private var motionGeneration = 0L
    private var sequence = 0L
    private var activeTicket: VerificationTicket? = null
    private var lastProof: OpticalVerificationEvidence? = null
    private var proofField: VerificationField? = null
    private var eligibleAtMs = 0L
    private var firstFailureAtMs: Long? = null

    @get:Synchronized val nextWakeAtMs: Long? get() = when {
        activeTicket != null -> activeTicket!!.requestedElapsedMs + policy.maxCapturePeriodMs
        motion != MotionPhase.STILL || state.phase == VerificationPhase.RETRY_LIMIT -> null
        state.needsReference -> eligibleAtMs
        lastProof != null -> lastProof!!.earliestObservedElapsedMs + policy.maxAbsoluteAgeMs
        else -> null
    }

    @Synchronized fun setTask(taskId: String, calibrationVersion: String) {
        if (this.taskId == taskId && this.calibrationVersion == calibrationVersion) return
        this.taskId = taskId
        this.calibrationVersion = calibrationVersion
        activeTicket = null; lastProof = null; proofField = null; firstFailureAtMs = null
        eligibleAtMs = 0
        state = PushToVerificationState(taskGeneration = state.taskGeneration + 1, motionEpoch = state.motionEpoch)
    }

    @Synchronized fun observeMotion(epoch: Long, phase: MotionPhase, nowElapsedMs: Long) {
        require(epoch >= 0 && nowElapsedMs >= 0)
        if (epoch != state.motionEpoch || phase == MotionPhase.MOVING && motion != MotionPhase.MOVING) {
            motionGeneration++
            activeTicket = null; lastProof = null; proofField = null
            state = state.copy(motionEpoch = epoch, needsReference = true, verifiedAtElapsedMs = null)
        }
        motion = phase
        tick(nowElapsedMs)
    }

    @Synchronized fun beginCapture(nowElapsedMs: Long, allowMovingDebug: Boolean = false): VerificationTicket? {
        tick(nowElapsedMs)
        if (taskId.isBlank() || activeTicket != null || motion != MotionPhase.STILL && !allowMovingDebug ||
            !state.needsReference || nowElapsedMs < eligibleAtMs || state.phase == VerificationPhase.RETRY_LIMIT) return null
        val ticket = VerificationTicket(state.taskGeneration, state.motionEpoch, motionGeneration, nowElapsedMs, ++sequence)
        activeTicket = ticket
        state = state.copy(phase = VerificationPhase.VERIFYING, rejectionReason = "")
        return ticket
    }

    @Synchronized fun complete(ticket: VerificationTicket, evidence: OpticalVerificationEvidence,
        field: VerificationField, nowElapsedMs: Long): Boolean {
        if (!owns(ticket)) return false
        val reason = when {
            motion != MotionPhase.STILL -> "exposure_motion"
            calibrationVersion.isBlank() || evidence.calibrationVersion != calibrationVersion || !evidence.calibrationValid -> "calibration_changed"
            !evidence.independentOpticalReference -> "not_independent_optical_reference"
            !evidence.qualityAccepted -> "optical_quality"
            evidence.exposureStartElapsedMs < ticket.requestedElapsedMs ||
                evidence.exposureEndElapsedMs < evidence.exposureStartElapsedMs ||
                evidence.observedElapsedMs !in evidence.exposureStartElapsedMs..evidence.exposureEndElapsedMs ||
                evidence.exposureEndElapsedMs > nowElapsedMs -> "observation_time"
            evidence.clockUncertaintyMs == null || !evidence.clockUncertaintyMs.isFinite() ||
                evidence.clockUncertaintyMs !in 0.0..policy.maxClockUncertaintyMs -> "observation_clock_unknown"
            nowElapsedMs - evidence.earliestObservedElapsedMs !in 0..policy.maxAbsoluteAgeMs -> "observation_stale"
            evidence.exposureMotionDeg == null || !evidence.exposureMotionDeg.isFinite() ||
                evidence.exposureMotionDeg < 0 || evidence.exposureMotionDeg > policy.maxExposureMotionDeg -> "exposure_motion"
            evidence.uncertaintyDeg == null || !evidence.uncertaintyDeg.isFinite() || evidence.uncertaintyDeg < 0 -> "unknown_error_budget"
            !listOf(evidence.opticalAltDeg, evidence.opticalAzDeg, evidence.targetAltDegAtObservation,
                evidence.targetAzDegAtObservation).all { it.isFinite() } ||
                evidence.opticalAltDeg !in -90.0..90.0 || evidence.targetAltDegAtObservation !in -90.0..90.0 -> "invalid_direction"
            else -> null
        }
        if (reason != null) { reject(ticket, nowElapsedMs, reason); return false }
        activeTicket = null
        lastProof = evidence
        proofField = field
        firstFailureAtMs = null
        eligibleAtMs = nowElapsedMs + policy.cooldownMs
        state = state.copy(needsReference = false, consecutiveFailures = 0, rejectionReason = "",
            phase = VerificationPhase.GUIDING, verifiedAtElapsedMs = null)
        tick(nowElapsedMs)
        return true
    }

    @Synchronized fun fail(ticket: VerificationTicket, nowElapsedMs: Long, reason: String = "solve_failed") {
        if (owns(ticket)) reject(ticket, nowElapsedMs, reason)
    }

    @Synchronized fun requestReference() {
        lastProof = null; proofField = null
        state = state.copy(needsReference = true, verifiedAtElapsedMs = null,
            phase = if (activeTicket != null) VerificationPhase.VERIFYING else VerificationPhase.NEEDS_REFERENCE)
    }

    @Synchronized fun retry(nowElapsedMs: Long) {
        activeTicket = null; firstFailureAtMs = null; eligibleAtMs = nowElapsedMs
        lastProof = null; proofField = null
        state = state.copy(needsReference = true, consecutiveFailures = 0, phase = VerificationPhase.NEEDS_REFERENCE,
            rejectionReason = "", verifiedAtElapsedMs = null)
    }

    /** Call from an elapsed-clock timer, including while a Dob and its IMU are stationary. */
    @Synchronized fun tick(nowElapsedMs: Long): PushToVerificationState {
        require(nowElapsedMs >= 0)
        activeTicket?.let { ticket ->
            if (nowElapsedMs - ticket.requestedElapsedMs >= policy.maxCapturePeriodMs) reject(ticket, nowElapsedMs, "capture_timeout")
            return state
        }
        val proof = lastProof
        if (proof != null) {
            val age = nowElapsedMs - proof.earliestObservedElapsedMs
            if (age < 0 || age >= policy.maxAbsoluteAgeMs) {
                lastProof = null; proofField = null
                state = state.copy(needsReference = true, verifiedAtElapsedMs = null, rejectionReason = "observation_stale")
            } else {
                val error = checkNotNull(proof.uncertaintyDeg) + age / 1000.0 * policy.untrackedSkyMotionDegPerSec
                val contains = proofField!!.contains(proof.opticalAltDeg, proof.opticalAzDeg,
                    proof.targetAltDegAtObservation, proof.targetAzDegAtObservation, error, state.isVerified)
                state = state.copy(phase = if (contains) VerificationPhase.VERIFIED else VerificationPhase.GUIDING,
                    verifiedAtElapsedMs = if (contains) proof.observedElapsedMs else null)
            }
        }
        state = state.copy(phase = when {
            motion == MotionPhase.MOVING -> VerificationPhase.MOVING
            motion == MotionPhase.SETTLING -> VerificationPhase.SETTLING
            state.consecutiveFailures >= policy.maxConsecutiveFailures || firstFailureAtMs?.let { nowElapsedMs - it >= policy.maxRetryPeriodMs } == true -> VerificationPhase.RETRY_LIMIT
            state.needsReference && nowElapsedMs < eligibleAtMs -> VerificationPhase.BACKOFF
            state.needsReference -> VerificationPhase.NEEDS_REFERENCE
            else -> state.phase
        })
        return state
    }

    private fun owns(ticket: VerificationTicket) = activeTicket == ticket &&
        ticket.taskGeneration == state.taskGeneration && ticket.motionEpoch == state.motionEpoch && ticket.motionGeneration == motionGeneration

    private fun reject(ticket: VerificationTicket, nowElapsedMs: Long, reason: String) {
        if (!owns(ticket)) return
        activeTicket = null; lastProof = null; proofField = null
        val failures = state.consecutiveFailures + 1
        if (firstFailureAtMs == null) firstFailureAtMs = nowElapsedMs
        val multiplier = 1L shl (failures - 1).coerceAtMost(12)
        eligibleAtMs = nowElapsedMs + (policy.cooldownMs * multiplier).coerceAtMost(policy.maximumBackoffMs)
        state = state.copy(needsReference = true, consecutiveFailures = failures, rejectionReason = reason, verifiedAtElapsedMs = null)
        tick(nowElapsedMs)
    }
}
