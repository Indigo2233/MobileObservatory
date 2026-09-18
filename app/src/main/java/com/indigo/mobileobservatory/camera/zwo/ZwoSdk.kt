package com.indigo.mobileobservatory.camera.zwo

/**
 * ASIOpenCamera / ASIInitCamera take the connected-camera index (0..n-1).
 * The Android JNI fills [com.zwo.ASICameraProperty.cameraID] with the first int
 * after the name field of ASI_CAMERA_INFO, which is MaxHeight (6388 on ASI6200),
 * not that index.
 */
internal object ZwoSdk {
    fun openIndex(connectedIndex: Int): Int = connectedIndex.coerceAtLeast(0)

    fun captureDirectBufferBytes(width: Int, height: Int, bytesPerPixel: Int): Int {
        val bytes = width.toLong().coerceAtLeast(1) *
            height.toLong().coerceAtLeast(1) *
            bytesPerPixel.coerceIn(1, 6).toLong()
        return bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** ZWO ASIGetVideoData reports TIMEOUT when the buffer is even slightly short. */
    fun captureGrabBufferBytes(width: Int, height: Int, bytesPerPixel: Int): Int {
        val image = captureDirectBufferBytes(width, height, bytesPerPixel).toLong()
        val row = width.toLong().coerceAtLeast(1) * bytesPerPixel.coerceIn(1, 6).toLong()
        return (image + row * 16 + 4096).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun maxPooledFrameBuffers(frameBytes: Int): Int = when {
        frameBytes >= 32 * 1024 * 1024 -> 1
        frameBytes >= 16 * 1024 * 1024 -> 2
        else -> 8
    }

    fun grabTimeoutMs(exposureUs: Float, frameBytes: Int): Int {
        val exposureMs = (exposureUs / 1000f).toInt().coerceAtLeast(0)
        val transferMs = (frameBytes / 25_000).coerceAtLeast(500)
        return (exposureMs + transferMs + 2000).coerceIn(3_000, 60_000)
    }

    /**
     * Live preview stays at bin 1 so ROI coordinates match recording.
     * Large-sensor USB cost is handled by grab timeout / buffer size, not by
     * silently switching bin (which resets ROI).
     */
    fun defaultPreviewBin(sensorWidth: Int, sensorHeight: Int, supportedBins: List<Int>): Int {
        if (sensorWidth < 1 || sensorHeight < 1) return 1
        val bins = supportedBins.filter { it > 0 }
        return if (1 in bins || bins.isEmpty()) 1 else bins.min()
    }

    fun sensorSizePlausible(width: Int, height: Int): Boolean =
        width in 64..30_000 && height in 64..30_000

    fun returnSucceeded(errorCode: Int?): Boolean = errorCode == 0

    fun nativeTargetToTenths(native: Long, wholeCelsius: Boolean): Int =
        if (wholeCelsius) (native * 10).toInt() else native.toInt()

    fun tenthsToNativeTarget(tenths: Int, wholeCelsius: Boolean): Long =
        if (wholeCelsius) (tenths / 10).toLong() else tenths.toLong()

    fun targetTempIsWholeCelsius(minValue: Long, maxValue: Long): Boolean =
        kotlin.math.abs(minValue) <= 100L && kotlin.math.abs(maxValue) <= 100L

    fun fallbackSensorSize(modelName: String): Pair<Int, Int>? {
        val name = modelName.uppercase()
        return when {
            "6200" in name -> 9576 to 6388
            "2600" in name -> 6244 to 4168
            "2400" in name -> 6072 to 4042
            "533" in name -> 3008 to 3008
            else -> null
        }
    }
}
