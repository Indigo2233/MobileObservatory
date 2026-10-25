package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.util.Lp700PreviewMapping
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class MediaImageEditSettings(
    val lp700: Boolean = false, val redGain: Float = 1f, val greenGain: Float = 1f,
    val blueGain: Float = 1f, val blackPoint: Float = 0f, val whitePoint: Float = 1f,
    val gamma: Float = 1f, val exposureStops: Float = 0f
)

enum class MediaImageExportFormat(val extension: String) { JPG("jpg"), PNG("png"), FIT("fit") }

/** Unsigned 16-bit mono / interleaved RGB. The range describes the original physical samples.
 * Processing consumes this range and quantizes once into display intensities 0..65535. */
data class MediaImagePixels(val width: Int, val height: Int, val channels: Int, val pixels: ShortArray,
    var range: MediaImageWindow = MediaImageWindow(0f, 1f)) {
    init {
        require(width > 0 && height > 0 && channels in listOf(1, 3))
        require(width.toLong() * height * channels == pixels.size.toLong())
    }
    fun deepCopy(): MediaImagePixels {
        MediaImageMemory.check(byteSize)
        return copy(pixels = pixels.copyOf())
    }
    fun unsigned(index: Int): Int = pixels[index].toInt() and 65535
    fun sample(index: Int): Double = range.black.toDouble() + unsigned(index) * range.span / 65535.0
    val byteSize: Long get() = pixels.size.toLong() * 2
}

data class MediaImageWindow(val black: Float, val white: Float) {
    init { require(black.isFinite() && white.isFinite() && white > black) }
    val span: Double get() = white.toDouble() - black
    fun encode(value: Double): Short {
        require(value.isFinite()) { "Non-finite image sample" }
        return (((value - black) / span).coerceIn(0.0, 1.0) * 65535).roundToInt().toShort()
    }
}

data class MediaImageRegion(val left: Int, val top: Int, val width: Int, val height: Int, val step: Int = 1) {
    init { require(left >= 0 && top >= 0 && width > 0 && height > 0 && step > 0) }
    val outputWidth: Int get() = ((width.toLong() + step - 1) / step).toInt()
    val outputHeight: Int get() = ((height.toLong() + step - 1) / step).toInt()
}

object MediaImageProcessing {
    fun window(image: MediaImagePixels, lowFraction: Double = .002, highFraction: Double = .998): MediaImageWindow {
        require(lowFraction >= 0 && highFraction <= 1 && lowFraction < highFraction)
        val histogram = IntArray(65536)
        val pixelCount = image.width * image.height
        val step = (pixelCount / 65_536).coerceAtLeast(1)
        var count = 0
        for (pixel in 0 until pixelCount step step) for (channel in 0 until image.channels) {
            histogram[image.unsigned(pixel * image.channels + channel)]++; count++
        }
        fun percentile(fraction: Double): Float {
            val rank = ((count - 1) * fraction).toInt(); var seen = 0
            for (code in histogram.indices) {
                seen += histogram[code]
                if (seen > rank) return (image.range.black + code * image.range.span / 65535).toFloat()
            }
            return image.range.white
        }
        val black = percentile(lowFraction); val white = percentile(highFraction)
        // Keep the measured sky at black even if sparse stars lie above the high percentile.
        return MediaImageWindow(black, maxOf(white, black + 1f))
    }

    fun lp700Gains(image: MediaImagePixels, window: MediaImageWindow = image.range): FloatArray {
        if (image.channels == 1) return floatArrayOf(1f, 1f, 1f)
        val step = ((image.width.toLong() * image.height) / 12_000).toInt().coerceAtLeast(1)
        val sums = DoubleArray(3); var count = 0
        for (pixel in 0 until image.width * image.height step step) {
            for (c in 0..2) sums[c] += ((image.sample(pixel * 3 + c) - window.black) / window.span).coerceAtLeast(0.0)
            count++
        }
        return Lp700PreviewMapping.whiteBalanceGains((sums[0] / count).toFloat(),
            (sums[1] / count).toFloat(), (sums[2] / count).toFloat())
    }

    fun whiteBalance(image: MediaImagePixels, lp700: Boolean, lpGains: FloatArray,
                     window: MediaImageWindow = image.range): FloatArray {
        if (image.channels == 1) return floatArrayOf(1f, 1f, 1f)
        val kernel = MediaImage16Kernel(image.range, window, MediaImageEditSettings(lp700 = lp700), lpGains)
        val sums = DoubleArray(3); var count = 0
        val step = ((image.width.toLong() * image.height) / 12_000).toInt().coerceAtLeast(1)
        for (pixel in 0 until image.width * image.height step step) {
            val i = pixel * 3
            val rgb = kernel.rgb(image.unsigned(i), image.unsigned(i + 1), image.unsigned(i + 2))
            val r = (rgb ushr 32 and 65535).toInt(); val g = (rgb ushr 16 and 65535).toInt(); val b = (rgb and 65535).toInt()
            val luma = (r * .299 + g * .587 + b * .114) / 65535
            if (luma !in .02.. .95) continue
            sums[0] += r.toDouble(); sums[1] += g.toDouble(); sums[2] += b.toDouble(); count++
        }
        if (count == 0) return floatArrayOf(1f, 1f, 1f)
        val target = (sums.sum() / 3).coerceAtLeast(.000001)
        return FloatArray(3) { (target / sums[it].coerceAtLeast(.000001)).toFloat().coerceIn(.5f, 2f) }
    }

    fun apply(image: MediaImagePixels, settings: MediaImageEditSettings, lpGains: FloatArray,
              window: MediaImageWindow = image.range, check: () -> Unit = {}) {
        MediaImage16Kernel(image.range, window, settings, lpGains, image.channels == 1).apply(image, check)
    }
}

/** Small integer LUTs and fixed-point scalar arithmetic; no image-sized FloatArray. */
class MediaImage16Kernel(range: MediaImageWindow, window: MediaImageWindow,
    settings: MediaImageEditSettings, lpGains: FloatArray, private val mono: Boolean = false) {
    private val normalized = run { MediaImageMemory.check(768 * 1024); LongArray(65536) { code ->
        val sample = range.black + code * range.span / 65535
        // Scalar headroom preserves channel ratios of bright stars outside a narrow stretch window.
        (((sample - window.black) / window.span) * 65535).coerceIn(-4294967295.0, 4294967295.0).roundToLong()
    } }
    private val tone = ShortArray(65536) { code ->
        val value = ((code / 65535.0 - settings.blackPoint.coerceIn(0f, .3f)) /
            (settings.whitePoint.coerceIn(.7f, 1f) - settings.blackPoint.coerceIn(0f, .3f))).coerceIn(0.0, 1.0)
        (value.pow(1.0 / settings.gamma.coerceIn(.5f, 2f)) * 65535).roundToInt().toShort()
    }
    private val lp = settings.lp700 && !mono
    private val lpScale = LongArray(3) { (lpGains[it] * 65536.0).roundToLong() }
    private val gains = floatArrayOf(settings.redGain, settings.greenGain, settings.blueGain).let { values ->
        LongArray(3) { ((if (mono) 1.0 else values[it].toDouble()) *
            2.0.pow(settings.exposureStops.coerceIn(-3f, 3f).toDouble()) * 65536).roundToLong() }
    }

    fun rgb(red: Int, green: Int, blue: Int): Long {
        var r = normalized[red].toLong(); var g = normalized[green].toLong(); var b = normalized[blue].toLong()
        if (lp) {
            val rr = r; val gg = g; val bb = b
            r = ((-382627L * rr - 832024L * gg + 2914387L * bb) / 1048576) * lpScale[0] / 65536
            g = ((74818L * rr + 1139212L * gg - 247884L * bb) / 1048576) * lpScale[1] / 65536
            b = ((3341727L * rr - 1624249L * gg - 1769771L * bb) / 1048576) * lpScale[2] / 65536
        }
        r = (r * gains[0] / 65536).coerceAtLeast(0)
        g = (g * gains[1] / 65536).coerceAtLeast(0)
        b = (b * gains[2] / 65536).coerceAtLeast(0)
        val luma = (r * 19595 + g * 38470 + b * 7471 + 32768) / 65536
        if (luma <= 0) return 0
        val target = (tone[luma.coerceIn(0, 65535).toInt()].toInt() and 65535).toLong()
        fun channel(value: Long) = ((value * target + luma / 2) / luma).coerceIn(0, 65535)
        return (channel(r) shl 32) or (channel(g) shl 16) or channel(b)
    }

    fun apply(image: MediaImagePixels, check: () -> Unit = {}) {
        for (pixel in 0 until image.width * image.height) {
            if (pixel % 8192 == 0) check()
            val i = pixel * image.channels; val r = image.unsigned(i)
            val output = rgb(r, if (image.channels == 3) image.unsigned(i + 1) else r,
                if (image.channels == 3) image.unsigned(i + 2) else r)
            image.pixels[i] = (output ushr 32).toShort()
            if (image.channels == 3) { image.pixels[i + 1] = (output ushr 16).toShort(); image.pixels[i + 2] = output.toShort() }
        }
        image.range = MediaImageWindow(0f, 1f)
    }
}
