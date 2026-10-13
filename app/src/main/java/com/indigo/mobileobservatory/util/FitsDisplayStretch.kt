package com.indigo.mobileobservatory.util

import kotlin.math.roundToInt
import kotlin.math.pow

/** Robust display window for FITS samples before they are converted to 8-bit pixels. */
object FitsDisplayStretch {
    const val DISPLAY_GAMMA = 1.55f

    data class Window(val black: Int, val white: Int) {
        val range: Int get() = (white - black).coerceAtLeast(1)

        fun normalize(value: Int): Float =
            ((value.toLong() - black.toLong()).toFloat() / range).coerceIn(0f, 1f)

        fun to8(value: Int): Int = (normalize(value) * 255f).toInt().coerceIn(0, 255)
    }

    /**
     * Finds black and white points from a histogram, so isolated hot/dead pixels do not
     * determine the display range.
     */
    fun window(values: IntArray, lowFraction: Double = 0.002, highFraction: Double = 0.998): Window {
        require(values.isNotEmpty()) { "FITS sample buffer is empty" }
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (value in values) {
            if (value < min) min = value
            if (value > max) max = value
        }
        if (max <= min) return Window(min, min + 1)

        return windowFromHistogram(
            min,
            max,
            values.size,
            lowFraction,
            highFraction
        ) { bins, span ->
            for (value in values) {
                val bin = (((value.toLong() - min.toLong()) * (bins.size - 1)) / span)
                    .toInt().coerceIn(0, bins.lastIndex)
                bins[bin]++
            }
        }
    }

    /** Same calculation for multiple planes without flattening them into another large array. */
    fun window(values: List<IntArray>, lowFraction: Double = 0.002, highFraction: Double = 0.998): Window {
        require(values.isNotEmpty() && values.all { it.isNotEmpty() }) { "FITS sample buffers are empty" }
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var count = 0
        for (array in values) {
            count += array.size
            for (value in array) {
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        if (max <= min) return Window(min, min + 1)

        return windowFromHistogram(
            min,
            max,
            count,
            lowFraction,
            highFraction
        ) { bins, span ->
            for (array in values) {
                for (value in array) {
                    val bin = (((value.toLong() - min.toLong()) * (bins.size - 1)) / span)
                        .toInt().coerceIn(0, bins.lastIndex)
                    bins[bin]++
                }
            }
        }
    }

    /** Applies a restrained luminance curve while preserving each pixel's channel ratios. */
    fun applyDisplayToneCurve(pixels: IntArray, gamma: Float = DISPLAY_GAMMA) {
        if (pixels.isEmpty() || gamma <= 0f) return
        val lut = IntArray(256) { value ->
            (255f * (value / 255f).toDouble().pow(gamma.toDouble())).roundToInt().coerceIn(0, 255)
        }
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val sourceR = (pixel ushr 16) and 0xFF
            val sourceG = (pixel ushr 8) and 0xFF
            val sourceB = pixel and 0xFF
            val luma = (sourceR * 299 + sourceG * 587 + sourceB * 114) / 1000
            if (luma == 0) continue
            val scale = lut[luma].toFloat() / luma
            val r = (sourceR * scale).roundToInt().coerceIn(0, 255)
            val g = (sourceG * scale).roundToInt().coerceIn(0, 255)
            val b = (sourceB * scale).roundToInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun windowFromHistogram(
        min: Int,
        max: Int,
        count: Int,
        lowFraction: Double,
        highFraction: Double,
        fill: (IntArray, Long) -> Unit
    ): Window {
        // 16-bit FITS data commonly uses the full signed range. A 16-bit histogram
        // keeps the percentile window stable even when a few outliers expand the span.
        val bins = IntArray(65_536)
        val span = max.toLong() - min.toLong()
        fill(bins, span)

        fun percentile(fraction: Double): Int {
            val target = (count * fraction.coerceIn(0.0, 1.0)).toInt()
                .coerceIn(0, count - 1)
            var cumulative = 0
            for (bin in bins.indices) {
                cumulative += bins[bin]
                if (cumulative > target) {
                    return (min.toLong() + (span * bin) / (bins.size - 1))
                        .toInt()
                }
            }
            return max
        }

        val black = percentile(lowFraction)
        val white = percentile(highFraction).coerceAtLeast(black + 1)
        return Window(black, white)
    }
}
