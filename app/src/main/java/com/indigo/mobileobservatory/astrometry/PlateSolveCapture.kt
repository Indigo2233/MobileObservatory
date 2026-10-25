package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.camera.FrameData
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil

fun plateSolveFrameWaitMs(exposureUs: Float): Long {
    require(exposureUs.isFinite() && exposureUs > 0)
    return (ceil(exposureUs.toDouble() / 1000).toLong() * 3 + 15_000L).coerceAtLeast(45_000L)
}

/** Reject exposures that could have begun before the mount settled; check connection every second. */
suspend fun awaitFreshPlateSolveFrame(
    exposureUs: Float,
    requestedAtMs: Long,
    elapsedMs: () -> Long,
    checkCamera: () -> Unit,
    read: suspend (Long) -> FrameData?
): FrameData? {
    val deadline = elapsedMs() + plateSolveFrameWaitMs(exposureUs)
    val earliestEnd = requestedAtMs + ceil(exposureUs.toDouble() / 1000).toLong()
    while (elapsedMs() < deadline) {
        currentCoroutineContext().ensureActive()
        checkCamera()
        val frame = read(minOf(1000L, deadline - elapsedMs()).coerceAtLeast(1))
        checkCamera()
        if (frame != null && frame.timestamp >= earliestEnd) return frame
    }
    return null
}
