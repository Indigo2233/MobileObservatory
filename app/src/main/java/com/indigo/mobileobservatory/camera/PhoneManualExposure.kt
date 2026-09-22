package com.indigo.mobileobservatory.camera

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Manual still-capture exposure limits and log-scale slider mapping.
 *
 * Ultra-wide HALs often advertise 0.1–0.5 s. When [maxFrameDurationNs] is longer,
 * still captures may accept that longer time; the UI uses the wider of the two,
 * capped at [UI_MAX_SECONDS].
 */
object PhoneManualExposure {
    const val UI_MAX_SECONDS = 8.0
    const val UI_MIN_SECONDS = 0.001
    const val ISO_AUTO = 0
    val BURST_PRESETS = listOf(1, 4, 8, 16)

    fun usableMaxExposureNs(advertisedNs: Long?, maxFrameDurationNs: Long?): Long {
        val advertised = advertisedNs ?: 2_000_000_000L
        val frame = maxFrameDurationNs ?: 0L
        val capNs = (UI_MAX_SECONDS * 1_000_000_000.0).toLong()
        return maxOf(advertised, frame).coerceAtMost(capNs).coerceAtLeast(advertised.coerceAtMost(capNs))
    }

    fun rangeSeconds(minAdvertisedSeconds: Double?, maxAdvertisedSeconds: Double?, maxFrameDurationNs: Long?): ClosedFloatingPointRange<Float> {
        val minS = (minAdvertisedSeconds ?: 0.01).coerceIn(UI_MIN_SECONDS, UI_MAX_SECONDS)
        val maxNs = usableMaxExposureNs(
            advertisedNs = maxAdvertisedSeconds?.let { (it * 1_000_000_000.0).toLong() },
            maxFrameDurationNs = maxFrameDurationNs
        )
        val maxS = maxOf(minS + UI_MIN_SECONDS, maxNs / 1_000_000_000.0).coerceAtMost(UI_MAX_SECONDS)
        return minS.toFloat()..maxS.toFloat()
    }

    fun toSlider(seconds: Float, min: Float, max: Float): Float {
        if (max <= min) return 0f
        val lo = ln(min.toDouble().coerceAtLeast(UI_MIN_SECONDS))
        val hi = ln(max.toDouble().coerceAtLeast(min.toDouble() + UI_MIN_SECONDS))
        val value = ln(seconds.toDouble().coerceIn(min.toDouble(), max.toDouble()))
        return ((value - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
    }

    fun fromSlider(position: Float, min: Float, max: Float): Float {
        if (max <= min) return min
        val t = position.coerceIn(0f, 1f).toDouble()
        val lo = ln(min.toDouble().coerceAtLeast(UI_MIN_SECONDS))
        val hi = ln(max.toDouble().coerceAtLeast(min.toDouble() + UI_MIN_SECONDS))
        return exp(lo + t * (hi - lo)).toFloat()
    }

    fun formatSeconds(seconds: Float): String {
        return when {
            seconds < 0.095f -> "1/${(1f / seconds).roundToInt().coerceAtLeast(1)} s"
            seconds < 1f -> "%.2f s".format(Locale.US, seconds)
            else -> "%.1f s".format(Locale.US, seconds)
        }
    }

    fun defaultBurstFrames(maxExposureSeconds: Float): Int =
        if (maxExposureSeconds <= 0.6f) 4 else 1
}
