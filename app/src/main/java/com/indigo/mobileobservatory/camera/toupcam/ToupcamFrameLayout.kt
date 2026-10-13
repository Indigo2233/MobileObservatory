package com.indigo.mobileobservatory.camera.toupcam

/** SDK resolution bounds the safe pull allocation; FrameInfo/FinalSize define dense payload. */
data class ToupcamFrameLayout(val width: Int, val height: Int, val payloadBytes: Int) {
    fun copyPayloadInto(sdkScratch: ByteArray, destination: ByteArray) {
        require(sdkScratch.size >= payloadBytes && destination.size == payloadBytes)
        System.arraycopy(sdkScratch, 0, destination, 0, payloadBytes)
    }
    companion object {
        fun validate(baseWidth: Int, baseHeight: Int, pulledWidth: Int, pulledHeight: Int,
            finalWidth: Int, finalHeight: Int, bytesPerPixel: Int, capacityBytes: Long): ToupcamFrameLayout? {
            if (baseWidth <= 0 || baseHeight <= 0 || pulledWidth <= 0 || pulledHeight <= 0 ||
                pulledWidth != finalWidth || pulledHeight != finalHeight || bytesPerPixel !in 1..6 || capacityBytes < 0) return null
            val basePixels = baseWidth.toLong() * baseHeight
            val payloadPixels = pulledWidth.toLong() * pulledHeight
            if (basePixels > Int.MAX_VALUE / bytesPerPixel || basePixels > capacityBytes / bytesPerPixel || payloadPixels > basePixels) return null
            val payload = payloadPixels * bytesPerPixel
            return ToupcamFrameLayout(pulledWidth, pulledHeight, payload.toInt())
        }
    }
}
