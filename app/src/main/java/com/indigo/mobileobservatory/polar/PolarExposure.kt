package com.indigo.mobileobservatory.polar

import java.math.BigDecimal
import kotlin.math.round

object PolarExposure {
    // SDKs can quantize exposure to their hardware step. A retained old setting must fail visibly.
    fun matchesReadback(requestedUs: Float, actualUs: Float): Boolean =
        requestedUs.isFinite() && actualUs.isFinite() && requestedUs > 0f && actualUs > 0f &&
            kotlin.math.abs(requestedUs - actualUs) <= maxOf(1f, requestedUs * .01f)

    fun parseUs(seconds: String, minimumUs: Float, maximumUs: Float): Float? {
        val value = seconds.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0 || !minimumUs.isFinite() || !maximumUs.isFinite()) return null
        val us = round(value * 1_000_000).toFloat()
        return us.takeIf { it.isFinite() && it > 0 && it in minimumUs..maximumUs }
    }

    fun secondsText(us: Float): String = BigDecimal.valueOf(us.toDouble() / 1_000_000).stripTrailingZeros().toPlainString()
}
