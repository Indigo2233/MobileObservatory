package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.flow.StateFlow

enum class ReadoutMode(val displayName: String) {
    NORMAL("Normal"),
    LCG("LCG"),
    HCG("HCG"),
    HDR("HDR"),
    LOW_NOISE("Low Noise");
}

interface Camera {
    val isOpen: StateFlow<Boolean>
    val isCapturing: StateFlow<Boolean>
    val cameraInfo: CameraInfo?
    val exposureRange: FloatRange
    val gainRange: FloatRange
    /** Device-native gain/ISO control description. */
    val gainCapability: GainCapability
        get() = GainCapability(
            min = gainRange.min,
            max = gainRange.max,
            defaultValue = gainRange.current
        )
    val currentExposureUs: Float
    val currentGain: Float
    val currentPixelFormat: PixelFormat
    val supportedPixelFormats: List<PixelFormat>
    val currentReadoutMode: ReadoutMode get() = ReadoutMode.NORMAL
    val supportedReadoutModes: List<ReadoutMode> get() = listOf(ReadoutMode.NORMAL)
    val currentRoi: Roi
    val cropInfo: CropInfo
    val hwExposureMaxUs: Float
    val roiMinWidth: Int get() = 8
    val roiMinHeight: Int get() = 8
    var longExposureEnabled: Boolean
    /** Industrial cameras only: multi-frame average beyond hardware exposure max. */
    val supportsSoftwareStacking: Boolean get() = false
    /** Sub-frame progress while software-stacking; null when idle. Pair(done, total). */
    val softwareStackingProgress: Pair<Int, Int>? get() = null

    fun close()
    fun startCapture(callback: FrameCallback)
    fun stopCapture()
    fun setExposureTime(us: Float)
    /** Writes a device-native gain value or ISO value. */
    fun setGain(value: Float)
    /** Optional display-only dB equivalent for devices with a documented conversion. */
    fun gainDbEquivalent(value: Float): Float? = null
    /**
     * Maps an automatic-exposure adjustment in exposure stops to a native value.
     * Adapters with an undocumented gain-to-exposure response should override this
     * method to return the current value until their mapping is validated.
     */
    fun adjustGainForExposure(stops: Float): Float {
        val capability = gainCapability
        val step = capability.step.takeIf { it > 0f } ?: 1f
        val multiplier = when {
            stops >= 0.5f -> 10f
            stops >= 0.25f -> 5f
            stops > 0f -> 1f
            stops <= -0.5f -> -10f
            stops <= -0.25f -> -5f
            stops < 0f -> -1f
            else -> 0f
        }
        return GainValueNormalizer.normalize(capability, currentGain + step * multiplier)
    }
    fun setPixelFormat(format: PixelFormat)
    fun setReadoutMode(mode: ReadoutMode) {}
    fun setRoi(roi: Roi)
    fun resetRoi()
    fun recycleBuffer(buf: ByteArray)
}

interface CameraOffsetCapable {
    val offsetSupported: Boolean
    val offsetLabel: String get() = "Offset"
    val offsetRange: FloatRange
    val offsetStep: Float get() = 1f
    val currentOffset: Float

    fun setOffset(value: Float)
}

/** Optional camera SDK control for the USB transfer bandwidth/traffic limit. */
interface CameraUsbBandwidthCapable {
    val usbBandwidthRange: IntRange?
    val currentUsbBandwidth: Int?

    /** Applies a device-native value and returns whether the SDK accepted it. */
    fun setUsbBandwidth(value: Int): Boolean
}

data class CameraNativeReadoutMode(
    val id: String,
    val displayName: String
)

interface CameraNativeReadoutModeCapable {
    val supportedNativeReadoutModes: List<CameraNativeReadoutMode>
    val currentNativeReadoutModeId: String

    fun setNativeReadoutMode(id: String): Boolean
}
