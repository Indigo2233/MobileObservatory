package com.indigo.mobileobservatory.camera

enum class PixelFormat {
    MONO8, MONO10, MONO12, MONO14, MONO16,
    BAYER_RG8, BAYER_GR8, BAYER_GB8, BAYER_BG8,
    BAYER_RG10, BAYER_GR10, BAYER_GB10, BAYER_BG10,
    BAYER_RG12, BAYER_GR12, BAYER_GB12, BAYER_BG12,
    BAYER_RG14, BAYER_GR14, BAYER_GB14, BAYER_BG14,
    BAYER_RG16, BAYER_GR16, BAYER_GB16, BAYER_BG16,
    RGB24, RGB48;

    val isBayer: Boolean get() = name.startsWith("BAYER_")
    val isMono: Boolean get() = name.startsWith("MONO")
    val isRgb: Boolean get() = name.startsWith("RGB")
    val is8bit: Boolean get() = name.endsWith("8") && !isRgb
    val is10bit: Boolean get() = !is8bit && !isRgb
    val isHighBit: Boolean get() = when (this) {
        RGB24 -> false
        RGB48 -> true
        else -> !is8bit
    }
    val bytesPerPixel: Int get() = when {
        this == RGB24 -> 3
        this == RGB48 -> 6
        is8bit -> 1
        else -> 2
    }
    val nativeBits: Int get() = when {
        this == RGB24 -> 8
        this == RGB48 -> 16
        name.endsWith("16") -> 16
        name.endsWith("14") -> 14
        name.endsWith("12") -> 12
        name.endsWith("10") -> 10
        else -> 8
    }
    val displayName: String get() = name.replace("_", " ")
}

data class CameraInfo(
    val name: String,
    val serialNumber: String,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val maxBitDepth: Int,
    val sensorName: String? = null,
    val pixelSizeUm: Float? = null
) {
    companion object {
        private val SENSOR_LOOKUP = mapOf(
            "MER-230" to "IMX174",
            "MER-131" to "ICX445",
            "MER-132" to "ICX445",
            "MER-500" to "MT9P031",
            "MER-630" to "ICX178",
            "MER-041" to "ICX205",
            "MER-030" to "ICX204",
            "MER-050" to "MT9P006",
            "MER-200" to "ICX274",
            "MER2-230" to "IMX174",
            "MER2-502" to "IMX250",
            "MER2-160" to "IMX273",
            "MER2-630" to "IMX178"
        )

        fun lookupSensor(modelName: String): String? {
            for ((prefix, sensor) in SENSOR_LOOKUP) {
                if (modelName.startsWith(prefix, ignoreCase = true)) return sensor
            }
            return null
        }
    }
}

data class FloatRange(val min: Float, val max: Float, val current: Float)

data class FrameData(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val frameId: Long,
    val timestamp: Long
) {
    val bytesPerPixel: Int get() = pixelFormat.bytesPerPixel
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameData) return false
        return frameId == other.frameId && width == other.width && height == other.height
    }
    override fun hashCode() = frameId.hashCode()
}

data class Roi(val x: Int, val y: Int, val width: Int, val height: Int)

data class CropInfo(val offsetX: Int, val offsetY: Int, val sdkWidth: Int, val sdkHeight: Int)

interface FrameCallback {
    fun onFrame(frame: FrameData)
}

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Enumerating : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val info: CameraInfo) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
