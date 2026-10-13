package com.indigo.mobileobservatory.pointing

enum class MotionPhase {
    STILL,
    MOVING,
    SETTLING
}

/**
 * Dob push-to motion gate. Plate-solve only when the tube is still; never while
 * it is being pushed. Mirrors StarSense Explorer's "hold still to align" usage
 * without keeping the camera streaming.
 */
class MotionGate(
    private val moveOnDegPerSec: Double = 0.12,
    private val stillBelowDegPerSec: Double = 0.035,
    private val settleMs: Long = 400,
    private val minPushDeg: Double = 0.02,
    private val motionNoiseFloorDegPerSec: Double = 0.015
) {
    var phase: MotionPhase = MotionPhase.STILL
        private set

    /** True after enough motion since the last capture attempt. */
    var movedSinceCapture: Boolean = false
        private set

    /**
     * A still window is waiting for a capture: first alignment, or after a push.
     * Consumed by [consumeAutoCapture] so the UI cannot double-fire.
     */
    var autoCaptureDue: Boolean = true
        private set
    /** Every detectable movement invalidates a verification of the previous still window. */
    var motionEpoch: Long = 0L
        private set
    val nextWakeAtMs: Long? get() = if (phase == MotionPhase.SETTLING && quietObserved) settleStartedMs + settleMs else null

    private var lastTimestampMs: Long? = null
    private var settleStartedMs: Long = 0L
    private var pushAccumDeg: Double = 0.0
    private var quietObserved = false

    fun ingest(rateDegPerSec: Double, timestampMs: Long): MotionPhase {
        require(rateDegPerSec.isFinite() && timestampMs >= 0)
        if (lastTimestampMs != null && timestampMs < lastTimestampMs!!) return phase
        val dtSec = lastTimestampMs?.let { (timestampMs - it) / 1000.0 } ?: 0.0
        lastTimestampMs = timestampMs
        val rate = kotlin.math.abs(rateDegPerSec)
        if (dtSec in 0.001..1.0 && rate >= motionNoiseFloorDegPerSec) {
            pushAccumDeg += kotlin.math.abs(rateDegPerSec) * dtSec
            if (pushAccumDeg >= minPushDeg) movedSinceCapture = true
        }

        val moving = rate >= moveOnDegPerSec || (movedSinceCapture && rate >= motionNoiseFloorDegPerSec)
        val quiet = rate < stillBelowDegPerSec && !moving
        if (moving && phase != MotionPhase.MOVING) {
            motionEpoch++
            movedSinceCapture = true
            autoCaptureDue = false
        }

        phase = when (phase) {
            MotionPhase.STILL -> if (moving) MotionPhase.MOVING else MotionPhase.STILL
            MotionPhase.MOVING -> {
                if (quiet) {
                    settleStartedMs = timestampMs
                    quietObserved = true
                    MotionPhase.SETTLING
                } else {
                    MotionPhase.MOVING
                }
            }
            MotionPhase.SETTLING -> when {
                moving -> MotionPhase.MOVING
                quiet && !quietObserved -> {
                    quietObserved = true
                    settleStartedMs = timestampMs
                    MotionPhase.SETTLING
                }
                quiet && timestampMs - settleStartedMs >= settleMs -> {
                    if (movedSinceCapture) autoCaptureDue = true
                    MotionPhase.STILL
                }
                else -> MotionPhase.SETTLING
            }
        }
        return phase
    }

    /** A clock can finish settling without waiting for another sensor event. */
    fun tick(nowMs: Long): MotionPhase {
        if (phase == MotionPhase.SETTLING && quietObserved && nowMs >= settleStartedMs + settleMs) {
            phase = MotionPhase.STILL
            if (movedSinceCapture) autoCaptureDue = true
        }
        return phase
    }

    /** Missing samples are not evidence of stillness. A new quiet sample is required. */
    fun invalidateForDiscontinuity(nowMs: Long) {
        require(nowMs >= 0)
        motionEpoch++
        movedSinceCapture = true
        autoCaptureDue = false
        phase = MotionPhase.SETTLING
        quietObserved = false
        settleStartedMs = nowMs
        lastTimestampMs = null
        pushAccumDeg = 0.0
    }

    /** Atomically take the pending auto-capture. */
    fun consumeAutoCapture(): Boolean {
        if (phase != MotionPhase.STILL || !autoCaptureDue) return false
        autoCaptureDue = false
        return true
    }

    fun notifyCaptureFinished(capturedMotionEpoch: Long = motionEpoch, success: Boolean = true) {
        if (capturedMotionEpoch != motionEpoch) return
        movedSinceCapture = false
        pushAccumDeg = 0.0
        autoCaptureDue = !success
    }

    fun requestVerification() { autoCaptureDue = true }

    /** On-target and still: do not keep shooting. */
    fun acknowledgeIdle() {
        if (phase == MotionPhase.STILL) autoCaptureDue = false
    }
}

object PushToAutoSolve {
    fun shouldFire(
        phase: MotionPhase,
        autoCaptureDue: Boolean,
        alreadySolved: Boolean,
        onTarget: Boolean,
        solving: Boolean,
        nowMs: Long,
        lastSolveEndedMs: Long,
        cooldownMs: Long = 1_500L,
        verifiedForCurrentMotion: Boolean = false
    ): Boolean {
        if (solving) return false
        if (phase != MotionPhase.STILL) return false
        if (!autoCaptureDue) return false
        if (nowMs - lastSolveEndedMs < cooldownMs) return false
        // Legacy plateSolved describes an older camera solution, not this movement's proof.
        if (verifiedForCurrentMotion && onTarget) return false
        return true
    }
}
