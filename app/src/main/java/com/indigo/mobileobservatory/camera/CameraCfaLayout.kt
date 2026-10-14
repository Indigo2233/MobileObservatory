package com.indigo.mobileobservatory.camera

import com.indigo.mobileobservatory.recording.RawRecordingNormalizer
import com.indigo.mobileobservatory.stacking.ChannelOrder
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import com.indigo.mobileobservatory.stacking.SerCfaInterpretation
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/** Interpretation only: no SDK output format or camera-owned sample bytes are changed. */
object CameraCfaLayout {
    fun effectiveFormat(format: PixelFormat, selection: SerCfaInterpretation): PixelFormat {
        if (selection == SerCfaInterpretation.AUTO) return format
        require(!format.isRgb) { "cfa_rgb_not_raw" }
        val prefix = when (selection) {
            SerCfaInterpretation.MONO -> "MONO"
            SerCfaInterpretation.RGGB -> "BAYER_RG"
            SerCfaInterpretation.BGGR -> "BAYER_BG"
            SerCfaInterpretation.GRBG -> "BAYER_GR"
            SerCfaInterpretation.GBRG -> "BAYER_GB"
            SerCfaInterpretation.AUTO -> error("unreachable")
        }
        return PixelFormat.valueOf("$prefix${format.nativeBits}")
    }

    fun effectiveTraits(traits: PixelTraits?, selection: SerCfaInterpretation): PixelTraits? =
        if (selection == SerCfaInterpretation.AUTO) traits else traits?.copy(bayerPatternAtOutput = true)

    fun interpret(frame: FrameData, selection: SerCfaInterpretation): FrameData {
        val format = effectiveFormat(frame.pixelFormat, selection)
        return if (format == frame.pixelFormat) frame else frame.copy(pixelFormat = format)
    }

    /** Preview/video encoders consume the CFA at the delivered array origin. Science keeps
     * its original sensor declaration and traits; manual selections are already at output. */
    fun displayFrame(frame: FrameData, traits: PixelTraits?): FrameData {
        val output = displayFormat(frame.pixelFormat, traits)
        return if (frame.pixelFormat == output) frame else frame.copy(pixelFormat = output)
    }
    fun displayFormat(format: PixelFormat, traits: PixelTraits?): PixelFormat {
        if (!format.isBayer || traits == null || traits.bayerPatternAtOutput) return format
        if (traits.binning % 2 == 0) return PixelFormat.valueOf("MONO${format.nativeBits}")
        val redX = (if (format.name.startsWith("BAYER_GR") || format.name.startsWith("BAYER_BG")) 1 else 0) xor (traits.roiX and 1)
        val redY = (if (format.name.startsWith("BAYER_GB") || format.name.startsWith("BAYER_BG")) 1 else 0) xor (traits.roiY and 1)
        val prefix = when { redY == 0 && redX == 0 -> "RG"; redY == 0 -> "GR"; redX == 0 -> "GB"; else -> "BG" }
        return PixelFormat.valueOf("BAYER_$prefix${format.nativeBits}")
    }

    data class Snapshot(val frame: FrameData, val traits: PixelTraits?)

    /** A private still owns its bytes and exact frame-time traits. Canonicalize known layouts
     * once, preserving every sensor code; downstream JPEG/FITS need not reread live settings. */
    fun ownedSnapshot(frame: FrameData, traits: PixelTraits?): Snapshot {
        val bytes = frame.width.toLong() * frame.height * frame.bytesPerPixel
        require(bytes in 1..Int.MAX_VALUE.toLong() && bytes <= frame.data.size) { "Truncated camera snapshot" }
        val owned = frame.copy(data = frame.data.copyOf(bytes.toInt()))
        return canonicalOwned(owned, traits)
    }

    /** For a recording queue's exclusively owned copy. Never call on an SDK/preview buffer. */
    fun canonicalOwned(frame: FrameData, traits: PixelTraits?): Snapshot {
        if (traits == null || traits.alignment == SampleAlignment.AUTO) return Snapshot(frame, traits)
        val canonical = RawRecordingNormalizer.normalizeOwnedInPlace(frame, traits)
        return Snapshot(canonical, traits.copy(byteOrder = ByteOrder.LITTLE_ENDIAN,
            alignment = SampleAlignment.RIGHT, validBits = canonical.pixelFormat.nativeBits,
            channelOrder = ChannelOrder.RGB, bayerPatternAtOutput = true))
    }

    fun boundSelection(saved: SerCfaInterpretation, savedLayout: String?, currentLayout: String): SerCfaInterpretation =
        if (saved == SerCfaInterpretation.AUTO || savedLayout == currentLayout) saved else SerCfaInterpretation.AUTO
}

/** Local run identity also protects cameras that do not expose an SDK generation token. */
class CameraPreviewEpoch {
    private val epoch = AtomicLong()
    fun nextRun(): Long = epoch.incrementAndGet()
    fun invalidate() { epoch.incrementAndGet() }
    fun isCurrent(run: Long): Boolean = epoch.get() == run
}

/** Called only inside PreviewPipeline's serialized renderer, after its predecessor finishes. */
class PreviewColorReset {
    private var completedIdentity: String? = null
    fun apply(identity: String, reset: () -> Unit) {
        if (completedIdentity == identity) return
        reset()
        // A queued/cancelled renderer must never claim that it already completed this reset.
        completedIdentity = identity
    }
}

data class CameraCfaChangeState(val connected: Boolean, val format: PixelFormat,
    val recording: Boolean = false, val calibration: Boolean = false, val snapshot: Boolean = false,
    val settings: Boolean = false, val stackExists: Boolean = false)

object CameraCfaChangePolicy {
    fun issue(state: CameraCfaChangeState, selection: SerCfaInterpretation, resetConfirmed: Boolean = false): String? = when {
        !state.connected -> "disconnected"
        state.format.isRgb && selection != SerCfaInterpretation.AUTO -> "rgb_not_raw"
        state.recording -> "recording"
        state.calibration -> "calibration"
        state.snapshot -> "snapshot"
        state.settings -> "settings"
        state.stackExists && !resetConfirmed -> "reset_stack"
        else -> null
    }
}
