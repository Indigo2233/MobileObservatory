package com.indigo.mobileobservatory.camera.toupcam

import com.indigo.mobileobservatory.camera.ReadoutMode

/** Restricts SDK capability bits to modes that the documented camera model supports. */
object ToupcamReadoutModes {
    data class Restored(val write: Int?, val current: ReadoutMode)

    fun advertised(
        modelName: String?,
        hasCg: Boolean,
        hasCgHdr: Boolean,
        hasLowNoise: Boolean
    ): List<ReadoutMode> {
        val modes = mutableListOf<ReadoutMode>()
        val documentedHdr = ToupTekDevices.supportsHdr(modelName)
        if (documentedHdr) {
            modes += ReadoutMode.LCG
            modes += ReadoutMode.HCG
            modes += ReadoutMode.HDR
        } else if (hasCg || hasCgHdr) {
            modes += ReadoutMode.LCG
            modes += ReadoutMode.HCG
        }
        if (hasLowNoise) modes += ReadoutMode.LOW_NOISE
        return modes.ifEmpty { listOf(ReadoutMode.NORMAL) }
    }

    fun current(cgValue: Int?, modes: List<ReadoutMode>): ReadoutMode = when (cgValue) {
        0 -> ReadoutMode.LCG.takeIf { it in modes } ?: modes.first()
        1 -> ReadoutMode.HCG.takeIf { it in modes } ?: modes.first()
        2 -> ReadoutMode.HDR.takeIf { it in modes } ?: modes.first()
        else -> modes.first()
    }

    /** A stored HDR value must not survive reconnect on a camera that does not expose HDR. */
    fun restoredModel(storedCg: Int?, advertised: List<ReadoutMode>, writable: Boolean): Restored {
        val normalTarget = when {
            ReadoutMode.LCG in advertised -> 0
            ReadoutMode.HCG in advertised -> 1
            ReadoutMode.NORMAL in advertised -> 0
            else -> null
        }
        if (storedCg == 2 && ReadoutMode.HDR !in advertised && writable && normalTarget != null) {
            return Restored(normalTarget, ToupcamReadoutModes.current(normalTarget, advertised))
        }
        return Restored(null, ToupcamReadoutModes.current(storedCg, advertised))
    }
}
