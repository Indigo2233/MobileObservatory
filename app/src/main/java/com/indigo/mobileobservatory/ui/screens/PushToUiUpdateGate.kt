package com.indigo.mobileobservatory.ui.screens

/** Keeps sensor processing independent from the lower UI publication rate. */
internal class PushToUiUpdateGate(private val minimumPeriodMs: Long = DEFAULT_PERIOD_MS) {
    private var lastPublishedElapsedMs: Long? = null

    init {
        require(minimumPeriodMs > 0)
    }

    fun shouldPublish(elapsedMs: Long): Boolean {
        require(elapsedMs >= 0)
        val previous = lastPublishedElapsedMs
        if (previous == null || elapsedMs < previous || elapsedMs - previous >= minimumPeriodMs) {
            lastPublishedElapsedMs = elapsedMs
            return true
        }
        return false
    }

    fun reset() {
        lastPublishedElapsedMs = null
    }

    companion object {
        /** 15 Hz is sufficient for animated guidance and leaves time for touch input. */
        const val DEFAULT_PERIOD_MS = 66L
    }
}
