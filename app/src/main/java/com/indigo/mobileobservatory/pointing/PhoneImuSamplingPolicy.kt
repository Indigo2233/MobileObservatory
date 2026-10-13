package com.indigo.mobileobservatory.pointing

/** Requested rates, not a promise about the device's actual sensor delivery frequency. */
internal enum class PhoneImuSamplingMode(val periodUs: Int) {
    ACTIVE(20_000),
    // Keep a 2.5x margin inside the timeline's 250ms interpolation/motion boundary.
    IDLE(100_000)
}

/** Monotonic quiet-time hysteresis and scoped verification demand; no calibration state lives here. */
internal class PhoneImuSamplingPolicy(private val idleAfterMs: Long = 5_000) {
    private var running = false
    private var phase = MotionPhase.SETTLING
    private var quietSinceMs: Long? = null
    private var nextLease = 0L
    private val verificationLeases = mutableSetOf<Long>()

    init { require(idleAfterMs > 0) }

    @Synchronized fun start() {
        running = true
        phase = MotionPhase.SETTLING
        quietSinceMs = null
        verificationLeases.clear()
    }

    @Synchronized fun stop() {
        running = false
        quietSinceMs = null
        verificationLeases.clear()
    }

    @Synchronized fun observeMotion(value: MotionPhase, elapsedNowMs: Long) {
        require(elapsedNowMs >= 0)
        if (!running) return
        if (value != MotionPhase.STILL) quietSinceMs = null
        else if (phase != MotionPhase.STILL || quietSinceMs == null) quietSinceMs = elapsedNowMs
        phase = value
    }

    @Synchronized fun requestVerification(elapsedNowMs: Long) {
        require(elapsedNowMs >= 0)
        if (running) quietSinceMs = if (phase == MotionPhase.STILL) elapsedNowMs else null
    }

    @Synchronized fun beginVerification(elapsedNowMs: Long): Long? {
        if (!running) return null
        requestVerification(elapsedNowMs)
        return (++nextLease).also { verificationLeases.add(it) }
    }

    @Synchronized fun endVerification(lease: Long?, elapsedNowMs: Long) {
        // An old coroutine's finally block cannot release a new session's lease after stop/start.
        if (lease != null && verificationLeases.remove(lease)) requestVerification(elapsedNowMs)
    }

    @Synchronized fun desiredMode(elapsedNowMs: Long): PhoneImuSamplingMode? {
        require(elapsedNowMs >= 0)
        if (!running) return null
        val quiet = quietSinceMs
        return if (verificationLeases.isEmpty() && phase == MotionPhase.STILL && quiet != null &&
            elapsedNowMs >= quiet && elapsedNowMs - quiet >= idleAfterMs) PhoneImuSamplingMode.IDLE
        else PhoneImuSamplingMode.ACTIVE
    }
}

internal enum class PhoneImuSampleDisposition { ACCEPT, IGNORE, DISCONTINUITY }

/** The two Android queues can overlap or deliver an older first sample during a rate transition. */
internal class PhoneImuSampleOrdering {
    private var activeTimestampNs = 0L
    private var idleTimestampNs = 0L
    private var acceptedTimestampNs = 0L

    @Synchronized fun clear() { activeTimestampNs = 0; idleTimestampNs = 0; acceptedTimestampNs = 0 }

    /** A new Android queue may begin with a cached event from the previous registration. */
    @Synchronized fun activeQueueStarted() { activeTimestampNs = 0 }

    @Synchronized fun classify(mode: PhoneImuSamplingMode, timestampNs: Long,
                               selectedMode: PhoneImuSamplingMode): PhoneImuSampleDisposition {
        // Keep accepting the continuous idle queue while a new active queue starts up. Its
        // samples prevent a rate transition from manufacturing a gap before the first fast event.
        if (mode == PhoneImuSamplingMode.ACTIVE && selectedMode != PhoneImuSamplingMode.ACTIVE)
            return PhoneImuSampleDisposition.IGNORE
        val previous = if (mode == PhoneImuSamplingMode.ACTIVE) activeTimestampNs else idleTimestampNs
        if (mode == PhoneImuSamplingMode.ACTIVE) activeTimestampNs = timestampNs else idleTimestampNs = timestampNs
        if (timestampNs <= 0 || (previous > 0 && timestampNs <= previous)) {
            acceptedTimestampNs = timestampNs
            return PhoneImuSampleDisposition.DISCONTINUITY
        }
        if (timestampNs <= acceptedTimestampNs) return PhoneImuSampleDisposition.IGNORE
        val gap = acceptedTimestampNs > 0 && timestampNs - acceptedTimestampNs > MAX_GAP_NS
        acceptedTimestampNs = timestampNs
        return if (gap) PhoneImuSampleDisposition.DISCONTINUITY else PhoneImuSampleDisposition.ACCEPT
    }

    companion object { const val MAX_GAP_NS = 500_000_000L }
}
