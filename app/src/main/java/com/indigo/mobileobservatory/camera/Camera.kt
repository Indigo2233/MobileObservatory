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
    fun adjustGainForExposure(stops: Float): Float =
        GainValueNormalizer.adjustForExposureStops(gainCapability, currentGain, stops)
    fun setPixelFormat(format: PixelFormat)
    fun setReadoutMode(mode: ReadoutMode) {}
    fun setRoi(roi: Roi)
    fun resetRoi()
    fun recycleBuffer(buf: ByteArray)
    /** False for Live View bodies that cannot crop the sensor from the host. */
    val supportsHostRoi: Boolean get() = true
    /** False when startCapture frames are preview-only and must not be saved as SER/FITS. */
    val recordsLiveViewAsScience: Boolean get() = true
}

enum class DslrStillFormat { JPEG, RAW, JPEG_PLUS_RAW }

data class DslrStillResult(
    val jpegFile: java.io.File?,
    val rawFile: java.io.File?,
    val iso: Int,
    val exposureUs: Long,
    val bulb: Boolean
)

interface CameraStillCaptureCapable {
    val stillCaptureSupported: Boolean
    val supportedStillFormats: List<DslrStillFormat>
    fun captureStill(format: DslrStillFormat, outputDir: java.io.File): DslrStillResult
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

/** Optional on-camera NxN binning. App bin uses this when the factor is listed. */
interface CameraBinningCapable {
    val supportedHardwareBins: List<Int>
    val currentHardwareBin: Int

    fun setHardwareBin(bin: Int): Boolean
}
