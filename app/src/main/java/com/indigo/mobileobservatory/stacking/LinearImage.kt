package com.indigo.mobileobservatory.stacking

/** Owned, linear floating point samples. RGB is interleaved; no display processing is baked in.
 * Producers transfer ownership; consumers must copy [pixels] before retaining mutable snapshots.
 * Input is normalized to 0..1, but processing may preserve negative/above-white signal.
 */
data class LinearImage(val width: Int, val height: Int, val channels: Int, val pixels: FloatArray) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(channels == 1 || channels == 3) { "Only mono and interleaved RGB are supported" }
        require(width.toLong() * height <= MAX_PIXELS) { "Image exceeds the 16 MP image budget" }
        require(width.toLong() * height * channels == pixels.size.toLong()) { "Invalid pixel buffer length" }
    }

    val pixelCount: Int get() = width * height
    fun luminanceAt(index: Int): Float = if (channels == 1) pixels[index] else {
        val i = index * 3
        pixels[i] * 0.2126f + pixels[i + 1] * 0.7152f + pixels[i + 2] * 0.0722f
    }
    fun luminance(): FloatArray = FloatArray(pixelCount) { luminanceAt(it) }
    fun deepCopy(): LinearImage = copy(pixels = pixels.copyOf())

    companion object {
        const val MAX_PIXELS = 16_777_216L
        const val DEFAULT_STACK_PIXELS = 4_194_304L
    }
}
