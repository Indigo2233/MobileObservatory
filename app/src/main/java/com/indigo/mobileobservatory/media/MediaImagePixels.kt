package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.util.Lp700PreviewMapping
import kotlin.math.pow

data class MediaImageEditSettings(
    val lp700: Boolean = false,
    val redGain: Float = 1f,
    val greenGain: Float = 1f,
    val blueGain: Float = 1f,
    val blackPoint: Float = 0f,
    val whitePoint: Float = 1f,
    val gamma: Float = 1f,
    val exposureStops: Float = 0f
)

enum class MediaImageExportFormat(val extension: String) { JPG("jpg"), PNG("png"), FIT("fit") }

/** Owned samples: mono or interleaved RGB. FITS input remains floating point through editing. */
data class MediaImagePixels(val width: Int, val height: Int, val channels: Int, val pixels: FloatArray) {
    init {
        require(width > 0 && height > 0 && channels in listOf(1, 3))
        require(width.toLong() * height * channels == pixels.size.toLong())
    }
    fun deepCopy() = copy(pixels = pixels.copyOf())
}

data class MediaImageWindow(val black: Float, val white: Float) {
    init { require(black.isFinite() && white.isFinite() && white > black) }
}

object MediaImageProcessing {
    /** One shared window preserves channel ratios and is reused by preview and full-resolution export. */
    fun window(image: MediaImagePixels): MediaImageWindow {
        val step = (image.pixels.size / 65_536).coerceAtLeast(1)
        val samples = FloatArray((image.pixels.size + step - 1) / step)
        var count = 0
        for (i in image.pixels.indices step step) {
            val value = image.pixels[i]
            require(value.isFinite()) { "Non-finite image sample" }
            samples[count++] = value
        }
        samples.sort()
        val black = samples[((count - 1) * 0.002).toInt()]
        val white = samples[((count - 1) * 0.998).toInt()]
        return if (white > black) MediaImageWindow(black, white)
            else MediaImageWindow(minOf(0f, black), maxOf(1f, white + 1f))
    }

    fun normalize(image: MediaImagePixels, window: MediaImageWindow, check: () -> Unit = {}) {
        val range = window.white.toDouble() - window.black
        for (i in image.pixels.indices) {
            if (i % 16_384 == 0) check()
            val value = ((image.pixels[i].toDouble() - window.black) / range).toFloat()
            require(value.isFinite()) { "Non-finite normalized image sample" }
            image.pixels[i] = value
        }
    }

    fun lp700Gains(image: MediaImagePixels): FloatArray {
        if (image.channels != 3) return floatArrayOf(1f, 1f, 1f)
        val step = ((image.width.toLong() * image.height) / 12_000).toInt().coerceAtLeast(1)
        val sums = DoubleArray(3)
        var count = 0
        for (pixel in 0 until image.width * image.height step step) {
            for (channel in 0..2) sums[channel] += image.pixels[pixel * 3 + channel].coerceAtLeast(0f).toDouble()
            count++
        }
        return Lp700PreviewMapping.whiteBalanceGains(
            (sums[0] / count).toFloat(), (sums[1] / count).toFloat(), (sums[2] / count).toFloat())
    }

    fun whiteBalance(image: MediaImagePixels, lp700: Boolean, lpGains: FloatArray): FloatArray {
        if (image.channels != 3) return floatArrayOf(1f, 1f, 1f)
        val sample = image.deepCopy()
        apply(sample, MediaImageEditSettings(lp700 = lp700), lpGains)
        val sums = DoubleArray(3)
        val step = ((sample.width.toLong() * sample.height) / 12_000).toInt().coerceAtLeast(1)
        var count = 0
        for (pixel in 0 until sample.width * sample.height step step) {
            val i = pixel * 3
            val luma = sample.pixels[i] * .299f + sample.pixels[i + 1] * .587f + sample.pixels[i + 2] * .114f
            if (luma !in .02f.. .95f) continue
            for (channel in 0..2) sums[channel] += sample.pixels[i + channel].toDouble()
            count++
        }
        if (count == 0) return floatArrayOf(1f, 1f, 1f)
        val target = (sums.sum() / 3.0).coerceAtLeast(0.000001)
        return FloatArray(3) { (target / sums[it].coerceAtLeast(0.000001)).toFloat().coerceIn(.5f, 2f) }
    }

    /** Mutates an owned result buffer. Quantization is deferred until JPG/PNG encoding. */
    fun apply(image: MediaImagePixels, settings: MediaImageEditSettings, lpGains: FloatArray,
              check: () -> Unit = {}) {
        val black = settings.blackPoint.coerceIn(0f, .30f)
        val white = settings.whitePoint.coerceIn(.70f, 1f)
        val exponent = 1.0 / settings.gamma.coerceIn(.5f, 2f)
        val exposure = 2.0.pow(settings.exposureStops.coerceIn(-3f, 3f).toDouble()).toFloat()
        val values = image.pixels
        for (pixel in 0 until image.width * image.height) {
            if (pixel % 8_192 == 0) check()
            val i = pixel * image.channels
            var red = values[i]
            var green = if (image.channels == 3) values[i + 1] else red
            var blue = if (image.channels == 3) values[i + 2] else red
            if (settings.lp700 && image.channels == 3) {
                val sourceRed = red; val sourceGreen = green; val sourceBlue = blue
                red = (-.3649013871f * sourceRed - .7934794639f * sourceGreen + 2.7793761244f * sourceBlue) * lpGains[0]
                green = (.0713524433f * sourceRed + 1.0864368006f * sourceGreen - .2364007716f * sourceBlue) * lpGains[1]
                blue = (3.1869189013f * sourceRed - 1.5490042912f * sourceGreen - 1.6877848393f * sourceBlue) * lpGains[2]
            }
            red = (red * if (image.channels == 3) settings.redGain else 1f).coerceAtLeast(0f) * exposure
            green = (green * if (image.channels == 3) settings.greenGain else 1f).coerceAtLeast(0f) * exposure
            blue = (blue * if (image.channels == 3) settings.blueGain else 1f).coerceAtLeast(0f) * exposure
            val luma = red * .299f + green * .587f + blue * .114f
            val tone = ((luma - black) / (white - black)).coerceIn(0f, 1f).toDouble().pow(exponent).toFloat()
            val gain = if (luma > 0f) tone / luma else 0f
            values[i] = (red * gain).coerceIn(0f, 1f)
            require(values[i].isFinite()) { "Non-finite processed sample" }
            if (image.channels == 3) {
                values[i + 1] = (green * gain).coerceIn(0f, 1f)
                values[i + 2] = (blue * gain).coerceIn(0f, 1f)
                require(values[i + 1].isFinite() && values[i + 2].isFinite()) { "Non-finite processed sample" }
            }
        }
    }
}
