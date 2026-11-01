package com.indigo.mobileobservatory.stacking

/** Read-only linear samples consumed synchronously by the registration/accumulation worker. */
interface LinearSamples {
    val width: Int
    val height: Int
    val channels: Int
    val pixelCount: Int get() = width * height
    val storageBytes: Long
    val encoding: String get() = "float32"
    val maximumQuantizationError: Double get() = 0.0

    fun sample(index: Int): Float
    fun allFinite(): Boolean

    fun luminanceSample(pixel: Int): Double {
        val i = pixel * channels
        return if (channels == 1) sample(i).toDouble() else
            sample(i) * 0.2126 + sample(i + 1) * 0.7152 + sample(i + 2) * 0.0722
    }

    fun sampleBilinear(x0: Int, y0: Int, x1: Int, y1: Int, ax: Double, ay: Double, result: FloatArray) {
        val a = (y0 * width + x0) * channels
        val b = (y0 * width + x1) * channels
        val c = (y1 * width + x0) * channels
        val d = (y1 * width + x1) * channels
        for (channel in 0 until channels) {
            result[channel] = ((sample(a + channel) * (1 - ax) + sample(b + channel) * ax) * (1 - ay) +
                (sample(c + channel) * (1 - ax) + sample(d + channel) * ax) * ay).toFloat()
        }
    }
}
