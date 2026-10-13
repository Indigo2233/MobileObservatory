package com.indigo.mobileobservatory.util

import kotlin.math.abs

/** Lightweight Bayer demosaicing for the display-resolution image path. */
object BayerDemosaic {
    enum class Mode { BILINEAR, DIRECTIONAL }

    fun demosaic8(raw: IntArray, width: Int, height: Int, pattern: String, mode: Mode): IntArray {
        val output = IntArray(width * height)
        return demosaic8(raw, width, height, pattern, mode, output)
    }

    fun demosaic8(
        raw: IntArray,
        width: Int,
        height: Int,
        pattern: String,
        mode: Mode,
        output: IntArray
    ): IntArray {
        require(width > 0 && height > 0) { "Bayer image dimensions must be positive" }
        val pixelCount = width * height
        require(raw.size >= pixelCount) { "Bayer buffer is smaller than the image" }
        require(output.size >= pixelCount) { "Bayer output buffer is smaller than the image" }
        require(pattern == "RGGB" || pattern == "GRBG" || pattern == "GBRG" || pattern == "BGGR") {
            "Unsupported Bayer pattern: $pattern"
        }
        val (rX, rY) = when (pattern) {
            "RGGB" -> 0 to 0
            "GRBG" -> 1 to 0
            "GBRG" -> 0 to 1
            else -> 1 to 1 // BGGR, validated above
        }
        fun reflected(value: Int, size: Int): Int {
            if (size <= 1) return 0
            return when {
                value < 0 -> -value
                value >= size -> 2 * size - 2 - value
                else -> value
            }.coerceIn(0, size - 1)
        }
        fun at(x: Int, y: Int): Int = raw[reflected(y, height) * width + reflected(x, width)]
        fun isRed(x: Int, y: Int) = x % 2 == rX && y % 2 == rY
        fun isBlue(x: Int, y: Int) = x % 2 != rX && y % 2 != rY
        fun avg2(a: Int, b: Int): Int = (a + b) / 2
        fun avg4(a: Int, b: Int, c: Int, d: Int): Int = (a + b + c + d) / 4
        fun directional(a: Int, b: Int, c: Int, d: Int): Int {
            val first = abs(a - b)
            val second = abs(c - d)
            return when {
                first < second -> avg2(a, b)
                second < first -> avg2(c, d)
                else -> avg4(a, b, c, d)
            }
        }
        fun diagonal(x: Int, y: Int): Int {
            val nw = at(x - 1, y - 1); val ne = at(x + 1, y - 1)
            val sw = at(x - 1, y + 1); val se = at(x + 1, y + 1)
            return if (mode == Mode.BILINEAR) avg4(nw, ne, sw, se)
            else directional(nw, se, ne, sw)
        }
        fun cross(x: Int, y: Int): Int {
            val l = at(x - 1, y); val r = at(x + 1, y)
            val u = at(x, y - 1); val d = at(x, y + 1)
            return if (mode == Mode.BILINEAR) avg4(l, r, u, d) else directional(l, r, u, d)
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rawValue = at(x, y)
                val red: Int
                val green: Int
                val blue: Int
                when {
                    isRed(x, y) -> {
                        red = rawValue
                        green = cross(x, y)
                        blue = diagonal(x, y)
                    }
                    isBlue(x, y) -> {
                        blue = rawValue
                        green = cross(x, y)
                        red = diagonal(x, y)
                    }
                    else -> {
                        green = rawValue
                        // At a green sample, red is horizontal when the sample is
                        // on the red row and lies opposite the red column.
                        val redHorizontal = (x % 2 != rX) && (y % 2 == rY)
                        val redAxis = if (redHorizontal) {
                            avg2(at(x - 1, y), at(x + 1, y))
                        } else {
                            avg2(at(x, y - 1), at(x, y + 1))
                        }
                        val blueAxis = if (redHorizontal) {
                            avg2(at(x, y - 1), at(x, y + 1))
                        } else {
                            avg2(at(x - 1, y), at(x + 1, y))
                        }
                        red = redAxis
                        blue = blueAxis
                    }
                }
                output[y * width + x] = (0xFF shl 24) or
                    (red.coerceIn(0, 255) shl 16) or
                    (green.coerceIn(0, 255) shl 8) or
                    blue.coerceIn(0, 255)
            }
        }
        return output
    }
}
