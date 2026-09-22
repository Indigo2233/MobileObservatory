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
    private val moveOnDegPerSec: Double = 1.2,
    private val stillBelowDegPerSec: Double = 0.35,
    private val settleMs: Long = 400,
    private val minPushDeg: Double = 0.8
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

    private var lastTimestampMs: Long = 0L
    private var settleStartedMs: Long = 0L
    private var pushAccumDeg: Double = 0.0

    fun ingest(rateDegPerSec: Double, timestampMs: Long): MotionPhase {
        val dtSec = if (lastTimestampMs == 0L) 0.0 else (timestampMs - lastTimestampMs) / 1000.0
        lastTimestampMs = timestampMs
        if (dtSec in 0.001..1.0) {
            pushAccumDeg += kotlin.math.abs(rateDegPerSec) * dtSec
            if (pushAccumDeg >= minPushDeg) movedSinceCapture = true
        }

        val moving = rateDegPerSec >= moveOnDegPerSec
        val quiet = rateDegPerSec < stillBelowDegPerSec

        phase = when (phase) {
            MotionPhase.STILL -> if (moving) MotionPhase.MOVING else MotionPhase.STILL
            MotionPhase.MOVING -> {
                if (quiet) {
                    settleStartedMs = timestampMs
                    MotionPhase.SETTLING
                } else {
                    MotionPhase.MOVING
                }
            }
            MotionPhase.SETTLING -> when {
                moving -> MotionPhase.MOVING
                quiet && timestampMs - settleStartedMs >= settleMs -> {
                    if (movedSinceCapture) autoCaptureDue = true
                    MotionPhase.STILL
                }
                else -> MotionPhase.SETTLING
            }
        }
        return phase
    }

    /** Atomically take the pending auto-capture. */
    fun consumeAutoCapture(): Boolean {
        if (phase != MotionPhase.STILL || !autoCaptureDue) return false
        autoCaptureDue = false
        return true
    }

    fun notifyCaptureFinished() {
        movedSinceCapture = false
        pushAccumDeg = 0.0
        autoCaptureDue = false
    }

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
        cooldownMs: Long = 1_500L
    ): Boolean {
        if (solving) return false
        if (phase != MotionPhase.STILL) return false
        if (!autoCaptureDue) return false
        if (nowMs - lastSolveEndedMs < cooldownMs) return false
        if (alreadySolved && onTarget) return false
        return true
    }
}
