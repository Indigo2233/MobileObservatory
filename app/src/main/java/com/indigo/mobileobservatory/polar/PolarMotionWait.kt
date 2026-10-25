package com.indigo.mobileobservatory.polar

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

enum class PolarMoveOutcome { COMPLETE, NOT_STARTED, TIMED_OUT }

suspend fun awaitPolarMove(busy: () -> Boolean, distanceDeg: Double, rateDegPerSec: Double): PolarMoveOutcome {
    val started = withTimeoutOrNull(5000L) { while (!busy()) delay(100L); true } ?: false
    if (!started) return PolarMoveOutcome.NOT_STARTED
    val timeout = (distanceDeg / rateDegPerSec * 3000).toLong().coerceIn(20_000L, 300_000L)
    return if (withTimeoutOrNull(timeout) { while (busy()) delay(250L); true } == true)
        PolarMoveOutcome.COMPLETE else PolarMoveOutcome.TIMED_OUT
}
