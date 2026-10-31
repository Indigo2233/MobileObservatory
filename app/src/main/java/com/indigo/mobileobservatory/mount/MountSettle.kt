package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Every renewed movement restarts the mechanical settling interval before a new exposure. */
suspend fun awaitMountSettled(
    moving: () -> Boolean = { false },
    coordinates: suspend () -> MountCoordinates?,
    elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    val settled = withTimeoutOrNull(30_000L) {
        var previouslyMoving = false
        var previous = coordinates()
        var anchor = previous
        var quietSince = elapsedMs()
        while (true) {
            currentCoroutineContext().ensureActive()
            val current = coordinates()
            val now = elapsedMs()
            val changed = current?.let { value ->
                listOfNotNull(previous, anchor).any {
                    PrecisionGotoMath.angularSeparationDeg(it, value) > GotoArrival.MOTION_STABLE_DEG
                }
            } == true
            val active = moving()
            if (active || previouslyMoving || changed) {
                quietSince = now
                anchor = current
            }
            previouslyMoving = active
            previous = current
            if (!active && now - quietSince >= 3000L) return@withTimeoutOrNull true
            delay(250L)
        }
        @Suppress("UNREACHABLE_CODE") false
    }
    check(settled == true) { "Mount did not remain still for three seconds before exposure" }
}
