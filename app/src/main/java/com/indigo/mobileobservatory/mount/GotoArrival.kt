package com.indigo.mobileobservatory.mount

/**
 * When a GOTO command may end.
 *
 * STOP is a separate flag: it is shown only while a fast slew is in progress.
 * A mount that is already still does not need STOP, so a few quiet samples end
 * the command whether or not the reported residual is inside 3′.
 * After a fast slew, the same quiet samples mean the axes have stopped.
 */
internal object GotoArrival {
    const val ON_TARGET_DEG = 0.05
    const val ON_TARGET_SAMPLES = 2

    /** Faster than sidereal tracking between 750 ms polls, slower than a slew. */
    const val MOTION_STABLE_DEG = 0.01
    const val MOTION_STABLE_SAMPLES = 3

    data class State(
        val onTargetSamples: Int = 0,
        val stableSamples: Int = 0,
        val sawSlewMotion: Boolean = false
    )

    enum class Decision { CONTINUE, ARRIVED }

    fun step(
        state: State,
        errorDeg: Double,
        movedSincePreviousDeg: Double?
    ): Pair<State, Decision> {
        val onTargetSamples = if (errorDeg <= ON_TARGET_DEG) {
            state.onTargetSamples + 1
        } else {
            0
        }
        if (onTargetSamples >= ON_TARGET_SAMPLES) {
            return State(onTargetSamples, state.stableSamples, state.sawSlewMotion) to Decision.ARRIVED
        }
        if (movedSincePreviousDeg == null) {
            return State(onTargetSamples, state.stableSamples, state.sawSlewMotion) to Decision.CONTINUE
        }
        val sawSlewMotion = state.sawSlewMotion || movedSincePreviousDeg > MOTION_STABLE_DEG
        val stableSamples = if (movedSincePreviousDeg <= MOTION_STABLE_DEG) {
            state.stableSamples + 1
        } else {
            0
        }
        val arrived = stableSamples >= MOTION_STABLE_SAMPLES
        return State(onTargetSamples, stableSamples, sawSlewMotion) to
            if (arrived) Decision.ARRIVED else Decision.CONTINUE
    }
}
