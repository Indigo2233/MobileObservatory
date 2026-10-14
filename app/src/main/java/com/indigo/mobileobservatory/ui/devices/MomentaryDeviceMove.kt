package com.indigo.mobileobservatory.ui.devices

/** Owns only a press-and-hold command; closing a panel must not abort planned GOTO. */
internal class MomentaryDeviceMove(private val start: () -> Unit, private val stop: () -> Unit) {
    private var held = false

    fun begin() {
        if (held) return
        held = true
        try {
            start()
        } catch (failure: Throwable) {
            end()
            throw failure
        }
    }

    fun end() {
        if (!held) return
        held = false
        stop()
    }
}
