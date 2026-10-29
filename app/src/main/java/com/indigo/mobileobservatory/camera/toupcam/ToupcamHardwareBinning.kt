package com.indigo.mobileobservatory.camera.toupcam

import com.indigo.mobileobservatory.camera.Roi

/** Sensor binning uses the separate SDK API. Discovery never changes camera settings. */
internal class ToupcamHardwareBinning(private val port: Port, private val sensorWidth: Int, private val sensorHeight: Int) {
    data class Mode(val value: String, val method: String)
    data class Geometry(val bin: Int, val width: Int, val height: Int)
    interface Port {
        fun values(): List<String>
        fun methods(): List<String>
        fun mode(): Mode?
        fun setMode(mode: Mode): Boolean
        fun digitalBin(): Int?
        fun setDigitalBin(value: Int): Boolean
        fun roi(): Roi?
        fun setRoi(roi: Roi): Boolean
        fun finalSize(): Pair<Int, Int>
    }

    private val modes: Map<Int, Mode> = run {
        val methods = port.methods()
        val method = listOf("Average", "Add").firstOrNull { it in methods }
        val parsed = if (method == null) emptyMap() else port.values().mapNotNull { value ->
            val match = Regex("([1-9][0-9]*)x\\1").matchEntire(value) ?: return@mapNotNull null
            match.groupValues[1].toIntOrNull()?.takeIf { it in 1..32 }?.let { it to Mode(value, method) }
        }.toMap()
        if (1 in parsed) parsed else emptyMap()
    }
    val supportedBins: List<Int> = (modes.keys + 1).sorted()

    fun initialize(): Geometry? {
        if (modes.isEmpty()) return null
        val current = port.mode() ?: error("Cannot read sensor binning")
        return if (current.value == "1x1" && port.digitalBin() == 1) geometry(1) else
            apply(1) ?: error("Cannot initialize sensor binning")
    }

    /** A failed request restores the complete original geometry before software fallback. */
    fun apply(bin: Int): Geometry? {
        val target = modes[bin] ?: return null
        val previous = port.mode() ?: return null
        val digital = port.digitalBin() ?: return null
        val roi = port.roi() ?: return null
        try {
            check(port.setRoi(Roi(0, 0, 0, 0))) { "Cannot clear ROI before sensor binning" }
            if (digital != 1) check(port.setDigitalBin(1)) { "Cannot disable digital binning" }
            check(port.digitalBin() == 1) { "Digital binning readback differs" }
            check(port.setMode(target) && port.mode() == target) { "Sensor binning readback differs" }
            check(port.setRoi(Roi(0, 0, 0, 0))) { "Cannot reset binned ROI" }
            return geometry(bin)
        } catch (failure: Exception) {
            val restored = runCatching {
                check(port.setRoi(Roi(0, 0, 0, 0)))
                check(port.setMode(previous) && port.mode() == previous)
                check(port.setDigitalBin(digital) && port.digitalBin() == digital)
                check(port.setRoi(roi) && port.roi() == roi)
            }
            if (restored.isFailure) throw IllegalStateException("Cannot restore sensor binning; reconnect camera", failure)
            return null
        }
    }

    private fun geometry(bin: Int): Geometry {
        val (width, height) = port.finalSize()
        check(width in 1..(sensorWidth / bin) && height in 1..(sensorHeight / bin)) { "Invalid sensor-binned frame size" }
        return Geometry(bin, width, height)
    }
}
