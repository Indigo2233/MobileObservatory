package com.indigo.mobileobservatory.camera.playerone

import com.indigo.mobileobservatory.camera.Roi
import com.playeroneastronomy.camera.PoaImageFormat

/** Verifies SDK colour binning with sensor hardware bin and MONO_BIN disabled. */
internal class PlayerOneBinning(
    private val port: Port,
    private val sensorWidth: Int,
    private val sensorHeight: Int,
    private val colourSensor: Boolean,
    private val supportedBins: List<Int>
) {
    data class Control(val value: Boolean, val writable: Boolean)
    data class State(
        val bin: Int,
        val roi: Roi,
        val format: PoaImageFormat,
        val hardware: Control?,
        val mono: Control?
    )
    interface Port {
        fun read(): State
        fun setBin(bin: Int)
        fun setFormat(format: PoaImageFormat)
        fun setRoi(roi: Roi)
        fun setHardware(enabled: Boolean)
        fun setMono(enabled: Boolean)
    }

    @Volatile var state: State? = null
        private set

    val outputCfaConfirmed: Boolean
        get() = state?.let { it.bin > 1 && isBayer(it.format) && layoutKnown(it) } == true

    /** Capture is stopped throughout the transaction, including restoration after any failed readback. */
    fun apply(bin: Int, format: PoaImageFormat, roi: Roi? = null): State? {
        if (bin !in supportedBins || bin < 1) return null
        val maxWidth = sensorWidth / bin / PoaMapping.ROI_WIDTH_ALIGN * PoaMapping.ROI_WIDTH_ALIGN
        val maxHeight = sensorHeight / bin / PoaMapping.ROI_HEIGHT_ALIGN * PoaMapping.ROI_HEIGHT_ALIGN
        if (maxWidth < PoaMapping.ROI_MIN || maxHeight < PoaMapping.ROI_MIN) return null
        state = null
        val previous = port.read()
        check(previous.format == format) { "Player One image format readback differs" }
        state = previous.takeIf(::layoutKnown)
        var target = PoaMapping.alignRoi(roi ?: Roi(0, 0, maxWidth, maxHeight), maxWidth, maxHeight)
        val colourBin = bin > 1 && isBayer(format)
        if (colourBin) {
            // An even origin keeps the camera's advertised CFA at the delivered image origin.
            target = target.copy(x = target.x / 2 * 2, y = target.y / 2 * 2)
            if (!canDisable(previous.hardware) || !canDisable(previous.mono)) return null
        }
        state = null
        try {
            if (previous.bin != bin) port.setBin(bin)
            val selected = port.read()
            check(selected.bin == bin && selected.format == format) { "Player One bin/format readback differs" }
            if (colourBin) {
                disable(port.read().hardware, port::setHardware)
                disable(port.read().mono, port::setMono)
            } else {
                val hardware = selected.hardware
                if (hardware != null && hardware.writable && hardware.value != (bin > 1)) {
                    port.setHardware(bin > 1)
                    check(port.read().hardware?.value == (bin > 1)) { "Player One hardware bin readback differs" }
                }
            }
            // Setting bin or either control can reset SDK geometry.
            if (port.read().roi != target) port.setRoi(target)
            val applied = port.read()
            check(applied.bin == bin && applied.format == format && applied.roi == target && layoutKnown(applied)) {
                "Player One bin geometry/colour readback differs"
            }
            state = applied
            return applied
        } catch (failure: Exception) {
            try {
                restore(previous)
            } catch (restoreFailure: Exception) {
                restoreFailure.addSuppressed(failure)
                throw IllegalStateException("Cannot restore Player One binning; reconnect camera", restoreFailure)
            }
            return null
        }
    }

    /** Resume verifies the same output; it must not silently select a different binning algorithm. */
    fun verify(): State {
        val expected = checkNotNull(state) { "Player One binning state is unknown; reconnect camera" }
        return try {
            val actual = port.read()
            check(sameOutput(expected, actual) && layoutKnown(actual)) { "Player One binning changed before capture" }
            actual.also { state = it }
        } catch (failure: Exception) {
            state = null
            throw failure
        }
    }

    fun invalidate() {
        state = null
    }

    private fun restore(previous: State) {
        port.setBin(previous.bin)
        port.setFormat(previous.format)
        restoreControl(previous.hardware, port.read().hardware, port::setHardware)
        restoreControl(previous.mono, port.read().mono, port::setMono)
        port.setRoi(previous.roi)
        val restored = port.read()
        check(sameOutput(previous, restored) && controlsMatch(previous, restored)) {
            "Player One binning restoration readback differs"
        }
        state = restored.takeIf(::layoutKnown)
    }

    private fun restoreControl(expected: Control?, actual: Control?, write: (Boolean) -> Unit) {
        if (expected == null) return
        checkNotNull(actual) { "Player One bin control became unreadable" }
        if (expected.value != actual.value) {
            check(actual.writable) { "Player One bin control cannot be restored" }
            write(expected.value)
        }
    }

    private fun sameOutput(expected: State, actual: State): Boolean =
        expected.bin == actual.bin && expected.roi == actual.roi && expected.format == actual.format &&
            (expected.bin == 1 || controlsMatch(expected, actual))

    private fun controlsMatch(expected: State, actual: State): Boolean =
        (expected.hardware == null || expected.hardware.value == actual.hardware?.value) &&
            (expected.mono == null || expected.mono.value == actual.mono?.value)

    private fun layoutKnown(value: State): Boolean {
        if (value.bin !in supportedBins || value.bin < 1 || value.roi.x < 0 || value.roi.y < 0 ||
            value.roi.width < PoaMapping.ROI_MIN || value.roi.height < PoaMapping.ROI_MIN ||
            value.roi.width % PoaMapping.ROI_WIDTH_ALIGN != 0 ||
            value.roi.height % PoaMapping.ROI_HEIGHT_ALIGN != 0 ||
            value.roi.x.toLong() + value.roi.width > sensorWidth / value.bin ||
            value.roi.y.toLong() + value.roi.height > sensorHeight / value.bin) return false
        if (value.bin == 1 || !isBayer(value.format)) return true
        return value.hardware?.value == false && value.mono?.value == false &&
            value.roi.x % 2 == 0 && value.roi.y % 2 == 0
    }

    private fun isBayer(format: PoaImageFormat): Boolean = colourSensor &&
        (format == PoaImageFormat.RAW8 || format == PoaImageFormat.RAW16)

    private fun canDisable(control: Control?): Boolean = control != null && (!control.value || control.writable)

    private fun disable(control: Control?, write: (Boolean) -> Unit) {
        check(canDisable(control)) { "Player One colour bin control cannot be confirmed" }
        if (control?.value == true) write(false)
    }
}
