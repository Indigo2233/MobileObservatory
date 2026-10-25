package com.indigo.mobileobservatory.media

import android.graphics.Bitmap

data class MediaDisplayHistogram(val bins: List<IntArray>, val samples: Int, val clipped: Int) {
    companion object {
        fun fromArgb(pixels: IntArray): MediaDisplayHistogram {
            val bins = List(3) { IntArray(256) }
            var clipped = 0
            pixels.forEach { pixel ->
                val r = pixel ushr 16 and 255; val g = pixel ushr 8 and 255; val b = pixel and 255
                bins[0][r]++; bins[1][g]++; bins[2][b]++
                if (r == 255 || g == 255 || b == 255) clipped++
            }
            return MediaDisplayHistogram(bins, pixels.size, clipped)
        }
        fun read(bitmap: Bitmap): MediaDisplayHistogram {
            MediaImageMemory.check(2L * 1024 * 1024 + bitmap.width * 4L)
            val step = kotlin.math.ceil(kotlin.math.sqrt(bitmap.width.toDouble() * bitmap.height / 262_144)).toInt().coerceAtLeast(1)
            val samples = IntArray(((bitmap.width + step - 1) / step) * ((bitmap.height + step - 1) / step))
            val row = IntArray(bitmap.width)
            var index = 0
            for (y in 0 until bitmap.height step step) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in row.indices step step) samples[index++] = row[x]
            }
            return fromArgb(samples)
        }
    }
}
