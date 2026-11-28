package com.indigo.mobileobservatory.sequence

import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Half-light radius of the brightest compact source in a mono image.
 * Returns null when no source rises above the background.
 */
fun medianHalfLightRadius(pixels: IntArray, width: Int, height: Int): Double? {
    if (width < 3 || height < 3 || pixels.size < width * height) return null
    var sum = 0.0
    for (value in pixels) sum += value
    val mean = sum / pixels.size
    var peak = 0
    var peakIndex = -1
    for (index in pixels.indices) {
        if (pixels[index] > peak) {
            peak = pixels[index]
            peakIndex = index
        }
    }
    if (peakIndex < 0 || peak <= mean * 1.5) return null
    val cx = peakIndex % width
    val cy = peakIndex / width
    val radius = 8
    var flux = 0.0
    var momentX = 0.0
    var momentY = 0.0
    for (y in (cy - radius).coerceAtLeast(0)..(cy + radius).coerceAtMost(height - 1)) {
        for (x in (cx - radius).coerceAtLeast(0)..(cx + radius).coerceAtMost(width - 1)) {
            val value = (pixels[y * width + x] - mean).coerceAtLeast(0.0)
            flux += value
            momentX += value * x
            momentY += value * y
        }
    }
    if (flux <= 0.0) return null
    val centerX = momentX / flux
    val centerY = momentY / flux
    val samples = ArrayList<Pair<Double, Double>>()
    for (y in (cy - radius).coerceAtLeast(0)..(cy + radius).coerceAtMost(height - 1)) {
        for (x in (cx - radius).coerceAtLeast(0)..(cx + radius).coerceAtMost(width - 1)) {
            val value = (pixels[y * width + x] - mean).coerceAtLeast(0.0)
            if (value <= 0.0) continue
            samples += hypot(x - centerX, y - centerY) to value
        }
    }
    samples.sortBy { it.first }
    val half = flux / 2.0
    var accumulated = 0.0
    for ((distance, value) in samples) {
        accumulated += value
        if (accumulated >= half) return distance
    }
    return null
}

fun gaussianStar(width: Int, height: Int, sigma: Double, peak: Int = 10_000): IntArray {
    val pixels = IntArray(width * height)
    val cx = (width - 1) / 2.0
    val cy = (height - 1) / 2.0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val r2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            val value = peak * kotlin.math.exp(-r2 / (2.0 * sigma * sigma))
            pixels[y * width + x] = value.roundToInt()
        }
    }
    return pixels
}

fun expectedGaussianHalfLightRadius(sigma: Double): Double = sigma * sqrt(2.0 * kotlin.math.ln(2.0))

fun countStars(pixels: IntArray, width: Int, height: Int): Int {
    if (width < 3 || height < 3 || pixels.size < width * height) return 0
    var sum = 0.0
    for (value in pixels) sum += value
    val threshold = sum / pixels.size * 3.0
    var count = 0
    val step = if (width > 400) 2 else 1
    for (y in 1 until height - 1 step step) {
        val row = y * width
        for (x in 1 until width - 1 step step) {
            val value = pixels[row + x]
            if (value < threshold) continue
            if (value >= pixels[row - width + x] &&
                value >= pixels[row + width + x] &&
                value >= pixels[row + x - 1] &&
                value >= pixels[row + x + 1]
            ) {
                count += 1
            }
        }
    }
    return count
}

data class LumaImage(val pixels: IntArray, val width: Int, val height: Int, val scale: Int = 1)

fun monoLuma(data: ByteArray, width: Int, height: Int, bytesPerPixel: Int): LumaImage? {
    if (width <= 0 || height <= 0 || bytesPerPixel !in 1..2) return null
    if (width.toLong() * height > data.size / bytesPerPixel) return null
    val maxEdge = 320
    val step = maxOf(1, maxOf(width, height) / maxEdge)
    val outWidth = maxOf(1, width / step)
    val outHeight = maxOf(1, height / step)
    val out = IntArray(outWidth * outHeight)
    for (y in 0 until outHeight) {
        for (x in 0 until outWidth) {
            val offset = ((y * step) * width + x * step) * bytesPerPixel
            val low = data[offset].toInt() and 0xFF
            out[y * outWidth + x] = if (bytesPerPixel == 1) low
                else low or ((data[offset + 1].toInt() and 0xFF) shl 8)
        }
    }
    return LumaImage(out, outWidth, outHeight, scale = step)
}
