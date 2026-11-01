package com.indigo.mobileobservatory.stacking

/** Owned, linear floating point samples. RGB is interleaved; no display processing is baked in.
 * Producers transfer ownership; consumers must copy [pixels] before retaining mutable snapshots.
 * Input is normalized to 0..1, but processing may preserve negative/above-white signal.
 */
data class LinearImage(override val width: Int, override val height: Int, override val channels: Int,
                       val pixels: FloatArray) : LinearSamples {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(channels == 1 || channels == 3) { "Only mono and interleaved RGB are supported" }
        require(width.toLong() * height <= MAX_PIXELS) { "Image exceeds the 16 MP image budget" }
        require(width.toLong() * height * channels == pixels.size.toLong()) { "Invalid pixel buffer length" }
    }

    override val pixelCount: Int get() = width * height
    override val storageBytes: Long get() = pixels.size * 4L
    override fun sample(index: Int): Float = pixels[index]
    override fun allFinite(): Boolean = pixels.all { it.isFinite() }
    override fun luminanceSample(pixel: Int): Double = if (channels == 1) pixels[pixel].toDouble() else {
        val i = pixel * channels
        pixels[i] * 0.2126 + pixels[i + 1] * 0.7152 + pixels[i + 2] * 0.0722
    }
    override fun sampleBilinear(x0: Int, y0: Int, x1: Int, y1: Int, ax: Double, ay: Double, result: FloatArray) {
        val a = (y0 * width + x0) * channels
        val b = (y0 * width + x1) * channels
        val c = (y1 * width + x0) * channels
        val d = (y1 * width + x1) * channels
        for (channel in 0 until channels) {
            result[channel] = ((pixels[a + channel] * (1 - ax) + pixels[b + channel] * ax) * (1 - ay) +
                (pixels[c + channel] * (1 - ax) + pixels[d + channel] * ax) * ay).toFloat()
        }
    }
    fun luminanceAt(index: Int): Float = if (channels == 1) pixels[index] else {
        val i = index * 3
        pixels[i] * 0.2126f + pixels[i + 1] * 0.7152f + pixels[i + 2] * 0.0722f
    }
    fun luminance(): FloatArray = FloatArray(pixelCount) { luminanceAt(it) }
    fun deepCopy(): LinearImage = copy(pixels = pixels.copyOf())

    companion object {
        const val MAX_PIXELS = 16_777_216L
        /** Conservative standalone-reader default. Budget-admitted stacking passes MAX_PIXELS explicitly. */
        const val DEFAULT_STACK_PIXELS = 4_194_304L
    }
}
