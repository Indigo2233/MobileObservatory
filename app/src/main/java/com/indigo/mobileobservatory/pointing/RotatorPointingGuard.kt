package com.indigo.mobileobservatory.pointing

import kotlin.math.abs

/** The current rotator drives the primary imaging train. Display rotation is unrelated. */
internal class RotatorPointingGuard(private val invalidatePrimary: () -> Unit) {
    data class State(val connected: Boolean, val moving: Boolean, val angleDeg: Double,
                     val reversed: Boolean, val stepsPerDegree: Int)

    private var previous: State? = null

    /** Revoke captures before dispatch, including devices whose moving telemetry arrives late. */
    fun beforeCommand(connected: Boolean) {
        if (connected) invalidatePrimary()
    }

    fun observe(state: State) {
        val old = previous
        previous = state
        val connectionChanged = old?.connected != state.connected && (state.connected || old?.connected == true)
        val installationChanged = state.connected && old?.connected == true && (
            old.moving != state.moving || old.reversed != state.reversed ||
                old.stepsPerDegree != state.stepsPerDegree ||
                (!state.moving && angleChanged(old.angleDeg, state.angleDeg)))
        // Also revoke at motion completion, so captures started during motion cannot reappear.
        // No disk write for every intermediate motor tick. Reconnect requires re-verification:
        // the application cannot prove the camera was untouched while disconnected.
        if (connectionChanged || installationChanged) invalidatePrimary()
    }

    private fun angleChanged(a: Double, b: Double): Boolean {
        if (a.isFinite() != b.isFinite()) return true
        val delta = abs((b - a) % 360.0)
        return a.isFinite() && minOf(delta, 360.0 - delta) > 1e-7
    }
}
