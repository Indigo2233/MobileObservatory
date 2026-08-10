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
    fun setGain(db: Float)
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

data class CameraNativeReadoutMode(
    val id: String,
    val displayName: String
)

interface CameraNativeReadoutModeCapable {
    val supportedNativeReadoutModes: List<CameraNativeReadoutMode>
    val currentNativeReadoutModeId: String

    fun setNativeReadoutMode(id: String): Boolean
}
