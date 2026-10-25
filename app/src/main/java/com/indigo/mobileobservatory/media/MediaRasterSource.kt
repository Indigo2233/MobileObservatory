package com.indigo.mobileobservatory.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

internal fun openMediaSource(file: File, range: MediaImageWindow? = null, check: () -> Unit = {}): MediaPixelSource {
    if (file.extension.lowercase() in listOf("fit", "fits")) return MediaImageFitsCodec.open(file, range, check)
    val source = if (file.extension.equals("png", true) && MediaPng16Codec.is16Bit(file)) MediaPng16Codec.open(file) else MediaRasterSource(file)
    return try {
        val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        if (orientation in 2..8) OrientedMediaSource(source, orientation) else source
    } catch (failure: Throwable) { source.close(); throw failure }
}

/** JPEG / 8-bit PNG are region-decoded directly to a bounded native bitmap. */
@Suppress("DEPRECATION")
private class MediaRasterSource(file: File) : MediaPixelSource {
    private val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
    override val width = decoder.width
    override val height = decoder.height
    override val channels = 3
    override val range = MediaImageWindow(0f, 1f)
    override fun read(region: MediaImageRegion, check: () -> Unit): MediaImagePixels {
        require(region.left.toLong() + region.width <= width && region.top.toLong() + region.height <= height)
        val count = region.outputWidth.toLong() * region.outputHeight * 3
        require(count <= Int.MAX_VALUE) { "memory_budget" }
        var sample = 1
        while (sample <= region.step / 2) sample *= 2
        val dw = (region.width.toLong() + sample - 1) / sample
        val dh = (region.height.toLong() + sample - 1) / sample
        MediaImageMemory.check(count * 2 + dw * 4, dw * dh * 4)
        check()
        val input = decoder.decodeRegion(Rect(region.left, region.top, region.left + region.width, region.top + region.height),
            BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) }) ?: throw IOException("Could not decode image region")
        try {
            val values = ShortArray(count.toInt()); val row = IntArray(input.width)
            for (y in 0 until region.outputHeight) {
                check(); val sy = (y.toLong() * region.step / sample).toInt().coerceAtMost(input.height - 1)
                input.getPixels(row, 0, input.width, 0, sy, input.width, 1)
                for (x in 0 until region.outputWidth) {
                    val pixel = row[(x.toLong() * region.step / sample).toInt().coerceAtMost(input.width - 1)]
                    val alpha = pixel ushr 24 and 255; val i = (y * region.outputWidth + x) * 3
                    for (c in 0..2) values[i + c] = ((((pixel ushr (16 - c * 8)) and 255) * alpha * 257 + 127) / 255).toShort()
                }
            }
            return MediaImagePixels(region.outputWidth, region.outputHeight, 3, values)
        } finally { input.recycle() }
    }
    override fun close() = decoder.recycle()
}

/** Maps the requested sample grid through EXIF, including mirrored and rotated small regions. */
internal class OrientedMediaSource(private val source: MediaPixelSource, private val orientation: Int) : MediaPixelSource {
    private val swap = orientation in 5..8
    override val width = if (swap) source.height else source.width
    override val height = if (swap) source.width else source.height
    override val channels = source.channels
    override val range = source.range
    override val header = source.header
    override val displayNormalized = source.displayNormalized
    private fun inverse(x: Int, y: Int): Pair<Int, Int> = when (orientation) {
        2 -> source.width - 1 - x to y; 3 -> source.width - 1 - x to source.height - 1 - y
        4 -> x to source.height - 1 - y; 5 -> y to x; 6 -> y to source.height - 1 - x
        7 -> source.width - 1 - y to source.height - 1 - x; 8 -> source.width - 1 - y to x
        else -> x to y
    }
    override fun read(region: MediaImageRegion, check: () -> Unit): MediaImagePixels {
        require(region.left.toLong() + region.width <= width && region.top.toLong() + region.height <= height)
        val first = inverse(region.left, region.top)
        val last = inverse(region.left + (region.outputWidth - 1) * region.step, region.top + (region.outputHeight - 1) * region.step)
        val left = minOf(first.first, last.first); val top = minOf(first.second, last.second)
        val rectangle = MediaImageRegion(left, top, kotlin.math.abs(first.first - last.first) + 1, kotlin.math.abs(first.second - last.second) + 1, region.step)
        val input = source.read(rectangle, check)
        MediaImageMemory.check(input.byteSize * 2)
        val result = ShortArray(input.pixels.size)
        val start = inverse(region.left, region.top)
        val nextX = inverse(region.left + region.step, region.top)
        val nextY = inverse(region.left, region.top + region.step)
        val startIndex = (((start.second - top) / region.step) * input.width + (start.first - left) / region.step) * channels
        val deltaX = ((nextX.second - start.second) / region.step * input.width + (nextX.first - start.first) / region.step) * channels
        val deltaY = ((nextY.second - start.second) / region.step * input.width + (nextY.first - start.first) / region.step) * channels
        for (y in 0 until region.outputHeight) {
            check(); var from = startIndex + y * deltaY; var to = y * region.outputWidth * channels
            for (x in 0 until region.outputWidth) {
                for (c in 0 until channels) result[to + c] = input.pixels[from + c]
                from += deltaX; to += channels
            }
        }
        return MediaImagePixels(region.outputWidth, region.outputHeight, channels, result, range)
    }
    override fun close() = source.close()
}
