package com.indigo.mobileobservatory.mount

import kotlin.math.abs

/** Device observation. Null means unreported, unsupported, or temporarily unavailable. */
data class MountTrackingState(
    val enabled: Boolean? = null,
    val rate: MountTrackingRate? = null
)

internal object MountTrackingCodec {
    fun onStep(status: String, frequencyHz: Double? = null): MountTrackingState {
        // GU includes a park flag and mount type even while a GOTO is running.
        require(status.any { it in "pIPF" } && status.any { it in "EKA" }) {
            "Invalid OnStep tracking status: $status"
        }
        if ('n' in status) return MountTrackingState(false, MountTrackingRate.OFF)
        val rate = frequencyHz?.takeIf { it.isFinite() && it > 0 }?.let { hz ->
            when {
                abs(hz - 60.16427) < .01 -> MountTrackingRate.SIDEREAL
                abs(hz - 60.0) < .01 -> MountTrackingRate.SOLAR
                abs(hz - 57.9) < .01 -> MountTrackingRate.LUNAR
                else -> null
            }
        }
        return MountTrackingState(true, rate)
    }

    fun ioptron(status: String): MountTrackingState {
        require(status.matches(Regex("[+-]\\d{22}"))) { "Invalid iOptron tracking status: $status" }
        return when (status[18]) {
            '0', '6', '7' -> MountTrackingState(false, MountTrackingRate.OFF)
            '1', '3', '5' -> MountTrackingState(true, when (status[19]) {
                '0' -> MountTrackingRate.SIDEREAL
                '1' -> MountTrackingRate.LUNAR
                '2' -> MountTrackingRate.SOLAR
                else -> null // King and custom rates have no matching UI preset.
            })
            else -> MountTrackingState() // Slewing/flipping does not identify the tracking state.
        }
    }
}
