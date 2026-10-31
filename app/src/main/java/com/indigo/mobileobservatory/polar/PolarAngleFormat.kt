package com.indigo.mobileobservatory.polar

import kotlin.math.abs
import kotlin.math.roundToLong

/** Round once in arcseconds so a carry never produces a displayed 60 minutes or seconds. */
fun formatPolarAngle(degrees: Double, signed: Boolean = false, compact: Boolean = false): String {
    if (!degrees.isFinite()) return "—"
    val seconds = (abs(degrees) * 3600.0).roundToLong()
    val d = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    val sign = when {
        seconds == 0L -> ""
        degrees < 0.0 -> "−"
        signed -> "+"
        else -> ""
    }
    val value = if (compact) {
        buildString {
            if (d > 0) append("${d}°")
            if (m > 0) append("${m}′")
            if (s > 0 || isEmpty()) append("${s}″")
        }
    } else {
        "${d}° ${m.toString().padStart(2, '0')}′ ${s.toString().padStart(2, '0')}″"
    }
    return sign + value
}
