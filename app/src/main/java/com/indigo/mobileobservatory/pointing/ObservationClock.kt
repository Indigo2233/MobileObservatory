package com.indigo.mobileobservatory.pointing

/** Camera2 UNKNOWN timestamps cannot be compared to IMU time, even if they look similar. */
object ObservationClock {
    fun fromRealtimeSensor(sensorStartNs: Long?, durationNs: Long, timestampIsRealtime: Boolean,
                           utcNowMs: Long, elapsedNowNs: Long, mappingUncertaintyMs: Long = 2): ObservationStamp? {
        if (!timestampIsRealtime || sensorStartNs == null || sensorStartNs <= 0 || durationNs < 0) return null
        val midpointNs = sensorStartNs + durationNs / 2
        if (midpointNs > elapsedNowNs + 100_000_000L || elapsedNowNs - midpointNs > 180_000_000_000L) return null
        val elapsedMs = midpointNs / 1_000_000
        return ObservationStamp(utcNowMs - (elapsedNowNs - midpointNs) / 1_000_000, elapsedMs, durationNs / 1_000_000, mappingUncertaintyMs)
    }
}
