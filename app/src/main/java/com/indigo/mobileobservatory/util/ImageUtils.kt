package com.indigo.mobileobservatory.util

import android.graphics.Bitmap
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat

object ImageUtils {

    /**
     * Applies the infrared LP750 color matrix to normalized RGB values.
     * Author: 奎木娘HIP5447.
     * Source: https://space.bilibili.com/455218677
     */
    fun lp750Matrix(r: Float, g: Float, b: Float): FloatArray {
        return floatArrayOf(
            (-0.3649013871 * r - 0.7934794639 * g + 2.7793761244 * b).toFloat(),
            (0.0713524433 * r + 1.0864368006 * g - 0.2364007716 * b).toFloat(),
            (3.1869189013 * r - 1.5490042912 * g - 1.6877848393 * b).toFloat()
        )
    }

    /** Converts normalized RGB to a packed display pixel without per-pixel allocations. */
    fun lp750Argb(
        r: Float,
        g: Float,
        b: Float,
        redGain: Float = 1f,
        greenGain: Float = 1f,
        blueGain: Float = 1f
    ): Int {
        val convertedR = (-0.3649013871f * r - 0.7934794639f * g + 2.7793761244f * b)
        val convertedG = (0.0713524433f * r + 1.0864368006f * g - 0.2364007716f * b)
        val convertedB = (3.1869189013f * r - 1.5490042912f * g - 1.6877848393f * b)
        val outR = (convertedR * redGain * 255f).coerceIn(0f, 255f).toInt()
        val outG = (convertedG * greenGain * 255f).coerceIn(0f, 255f).toInt()
        val outB = (convertedB * blueGain * 255f).coerceIn(0f, 255f).toInt()
        return (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    /**
     * Computes per-image gray-world gains after LP750 conversion.
     * The image average is the source of the white-balance decision.
     */
    fun lp750WhiteBalanceGains(r: Float, g: Float, b: Float): FloatArray {
        val converted = lp750Matrix(r, g, b)
        val target = converted[1].coerceAtLeast(0.001f)
        return floatArrayOf(
            (target / converted[0].coerceAtLeast(0.001f)).coerceIn(0.25f, 4.0f),
            1.0f,
            (target / converted[2].coerceAtLeast(0.001f)).coerceIn(0.25f, 4.0f)
        )
    }

    /**
     * Computes a restrained gray-world correction from already converted pixels.
     * The selected pixels should represent the image background rather than bright stars.
     */
    fun lp750BackgroundWhiteBalanceGains(r: Float, g: Float, b: Float): FloatArray {
        val target = ((r + g + b) / 3f).coerceAtLeast(0.001f)
        fun gain(channel: Float): Float =
            (target / channel.coerceAtLeast(0.001f)).coerceIn(0.75f, 1.35f)
        return floatArrayOf(gain(r), gain(g), gain(b))
    }

    /** Applies image-derived background white balance to an LP750-converted ARGB buffer. */
    fun applyLp750BackgroundWhiteBalance(pixels: IntArray) {
        if (pixels.isEmpty()) return
        val histogram = IntArray(256)
        for (pixel in pixels) {
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            histogram[(r * 299 + g * 587 + b * 114) / 1000]++
        }

        fun percentile(fraction: Double): Int {
            val target = (pixels.size * fraction).toInt().coerceIn(0, pixels.size - 1)
            var cumulative = 0
            for (i in histogram.indices) {
                cumulative += histogram[i]
                if (cumulative > target) return i
            }
            return histogram.lastIndex
        }

        val low = percentile(0.10)
        val high = percentile(0.60).coerceAtLeast(low)
        val fadeEnd = (high + 32).coerceAtMost(255)
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0
        for (pixel in pixels) {
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            if (luma in low..high) {
                sumR += r / 255.0
                sumG += g / 255.0
                sumB += b / 255.0
                count++
            }
        }
        if (count == 0) return

        val gains = lp750BackgroundWhiteBalanceGains(
            (sumR / count).toFloat(),
            (sumG / count).toFloat(),
            (sumB / count).toFloat()
        )
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val sourceR = (pixel ushr 16) and 0xFF
            val sourceG = (pixel ushr 8) and 0xFF
            val sourceB = pixel and 0xFF
            val luma = (sourceR * 299 + sourceG * 587 + sourceB * 114) / 1000
            val weight = when {
                luma <= high -> 1f
                luma >= fadeEnd -> 0f
                else -> (fadeEnd - luma).toFloat() / (fadeEnd - high).coerceAtLeast(1)
            }
            val rGain = 1f + (gains[0] - 1f) * weight
            val gGain = 1f + (gains[1] - 1f) * weight
            val bGain = 1f + (gains[2] - 1f) * weight
            val r = (sourceR * rGain).toInt().coerceIn(0, 255)
            val g = (sourceG * gGain).toInt().coerceIn(0, 255)
            val b = (sourceB * bGain).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Converts normalized RGB values with the infrared LP750 color matrix.
     * The matrix is applied to the demosaiced/decoded channels before they are
     * quantized for display. Values outside the displayable range are clipped.
     */
    fun applyLp750Matrix(r: Float, g: Float, b: Float): IntArray {
        val converted = lp750Matrix(r, g, b)
        return intArrayOf(
            (converted[0] * 255f).coerceIn(0f, 255f).toInt(),
            (converted[1] * 255f).coerceIn(0f, 255f).toInt(),
            (converted[2] * 255f).coerceIn(0f, 255f).toInt()
        )
    }

    fun mono8ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val v = data[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun mono10ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val lo = data[i * 2].toInt() and 0xFF
            val hi = data[i * 2 + 1].toInt() and 0xFF
            val raw10 = (hi shl 8) or lo
            val v = (raw10 shr 2).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun formatExposure(us: Float): String {
        return when {
            us >= 100_000_000f -> "%.0fs".format(us / 1_000_000f)
            us >= 1_000_000f -> "%.2fs".format(us / 1_000_000f)
            us >= 1000f -> "%.1fms".format(us / 1000f)
            else -> "%.0fμs".format(us)
        }
    }

    /** Parse SharpCap-style exposure text: `10.5s`, `30ms`, `500us`/`500μs`, `5m`, or plain number. */
    fun parseExposureUs(text: String, defaultUnit: ExposureUnit = ExposureUnit.SECONDS): Float? {
        val trimmed = text.trim().lowercase().replace(',', '.')
        if (trimmed.isEmpty()) return null
        val match = Regex("""^\s*([+-]?\d+(?:\.\d+)?)\s*(us|μs|ms|s|m)?\s*$""").matchEntire(trimmed)
            ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        if (!value.isFinite() || value < 0f) return null
        val unit = when (match.groupValues[2]) {
            "us", "μs" -> ExposureUnit.MICROSECONDS
            "ms" -> ExposureUnit.MILLISECONDS
            "s" -> ExposureUnit.SECONDS
            "m" -> ExposureUnit.MINUTES
            else -> defaultUnit
        }
        return when (unit) {
            ExposureUnit.MICROSECONDS -> value
            ExposureUnit.MILLISECONDS -> value * 1_000f
            ExposureUnit.SECONDS -> value * 1_000_000f
            ExposureUnit.MINUTES -> value * 60_000_000f
        }
    }

    enum class ExposureUnit { MICROSECONDS, MILLISECONDS, SECONDS, MINUTES }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val h = m / 60
        return "%02d:%02d:%02d".format(h, m % 60, s % 60)
    }
}
