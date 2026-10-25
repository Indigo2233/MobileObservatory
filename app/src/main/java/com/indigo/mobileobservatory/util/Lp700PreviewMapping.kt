package com.indigo.mobileobservatory.util

/**
 * Display-only color mapping for data captured through a 700 nm infrared long-pass filter.
 * Algorithm author: 奎木娘HIP5447. Source: https://space.bilibili.com/455218677
 */
object Lp700PreviewMapping {
    data class BackgroundBalance(val gains: FloatArray, val high: Int, val fadeEnd: Int)
    data class ArgbRecipe(val gains: FloatArray, val background: BackgroundBalance)
    private const val MAX_GAIN = 4.0f
    private const val MIN_GAIN = 0.25f
    private const val SAMPLE_COUNT = 10_000

    fun matrix(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        (-0.3649013871 * r - 0.7934794639 * g + 2.7793761244 * b).toFloat(),
        (0.0713524433 * r + 1.0864368006 * g - 0.2364007716 * b).toFloat(),
        (3.1869189013 * r - 1.5490042912 * g - 1.6877848393 * b).toFloat()
    )

    fun whiteBalanceGains(r: Float, g: Float, b: Float): FloatArray {
        val converted = matrix(r, g, b)
        val target = converted[1].coerceAtLeast(0.001f)
        return floatArrayOf(
            (target / converted[0].coerceAtLeast(0.001f)).coerceIn(MIN_GAIN, MAX_GAIN),
            1.0f,
            (target / converted[2].coerceAtLeast(0.001f)).coerceIn(MIN_GAIN, MAX_GAIN)
        )
    }

    /** Applies the complete ARGB display recipe in place. */
    fun applyToArgb(
        pixels: IntArray,
        gains: FloatArray? = null,
        recipe: ArgbRecipe? = null,
        cancellationCheck: () -> Unit = {}
    ): ArgbRecipe {
        if (pixels.isEmpty()) return recipe ?: ArgbRecipe(floatArrayOf(1f, 1f, 1f), BackgroundBalance(floatArrayOf(1f, 1f, 1f), 0, 32))
        val effectiveGains = recipe?.gains ?: gains ?: averageArgbGains(pixels)
        for (index in pixels.indices) {
            if (index % 16_384 == 0) cancellationCheck()
            val pixel = pixels[index]
            pixels[index] = argb(
                ((pixel ushr 16) and 0xFF) / 255f,
                ((pixel ushr 8) and 0xFF) / 255f,
                (pixel and 0xFF) / 255f,
                effectiveGains[0],
                effectiveGains[1],
                effectiveGains[2]
            )
        }
        val background = applyBackgroundWhiteBalance(pixels, recipe?.background)
        return ArgbRecipe(effectiveGains, background)
    }

    /** Maps interleaved linear RGB while retaining floating-point precision for later display work. */
    fun mapLinearRgb(
        source: FloatArray,
        width: Int,
        cancellationCheck: () -> Unit = {}
    ): FloatArray {
        require(source.size % 3 == 0) { "LP700 linear RGB input must be interleaved" }
        if (source.isEmpty()) return FloatArray(0)
        val pixelCount = source.size / 3
        val step = (pixelCount / SAMPLE_COUNT).coerceAtLeast(1)
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var count = 0
        for (index in 0 until pixelCount step step) {
            red += source[index * 3]
            green += source[index * 3 + 1]
            blue += source[index * 3 + 2]
            count++
        }
        val gains = whiteBalanceGains(
            (red / count).toFloat(),
            (green / count).toFloat(),
            (blue / count).toFloat()
        )
        val mapped = FloatArray(source.size)
        for (index in 0 until pixelCount) {
            if (width > 0 && index % width == 0) cancellationCheck()
            val offset = index * 3
            val sourceRed = source[offset]
            val sourceGreen = source[offset + 1]
            val sourceBlue = source[offset + 2]
            mapped[offset] = ((-0.3649013871f * sourceRed - 0.7934794639f * sourceGreen + 2.7793761244f * sourceBlue) * gains[0])
                .coerceIn(0f, 1f)
            mapped[offset + 1] = ((0.0713524433f * sourceRed + 1.0864368006f * sourceGreen - 0.2364007716f * sourceBlue) * gains[1])
                .coerceIn(0f, 1f)
            mapped[offset + 2] = ((3.1869189013f * sourceRed - 1.5490042912f * sourceGreen - 1.6877848393f * sourceBlue) * gains[2])
                .coerceIn(0f, 1f)
        }
        return mapped
    }

    internal fun to8Bit(r: Float, g: Float, b: Float): IntArray {
        val converted = matrix(r, g, b)
        return IntArray(3) { channel -> (converted[channel] * 255f).coerceIn(0f, 255f).toInt() }
    }

    internal fun argb(
        r: Float,
        g: Float,
        b: Float,
        redGain: Float = 1f,
        greenGain: Float = 1f,
        blueGain: Float = 1f
    ): Int {
        val convertedR = -0.3649013871f * r - 0.7934794639f * g + 2.7793761244f * b
        val convertedG = 0.0713524433f * r + 1.0864368006f * g - 0.2364007716f * b
        val convertedB = 3.1869189013f * r - 1.5490042912f * g - 1.6877848393f * b
        val outR = (convertedR * redGain * 255f).coerceIn(0f, 255f).toInt()
        val outG = (convertedG * greenGain * 255f).coerceIn(0f, 255f).toInt()
        val outB = (convertedB * blueGain * 255f).coerceIn(0f, 255f).toInt()
        return (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    internal fun backgroundWhiteBalanceGains(r: Float, g: Float, b: Float): FloatArray {
        val target = ((r + g + b) / 3f).coerceAtLeast(0.001f)
        fun gain(channel: Float): Float =
            (target / channel.coerceAtLeast(0.001f)).coerceIn(0.75f, 1.35f)
        return floatArrayOf(gain(r), gain(g), gain(b))
    }

    private fun backgroundBalance(pixels: IntArray): BackgroundBalance {
        if (pixels.isEmpty()) return BackgroundBalance(floatArrayOf(1f, 1f, 1f), 0, 32)
        val histogram = IntArray(256)
        for (pixel in pixels) {
            val red = (pixel ushr 16) and 0xFF
            val green = (pixel ushr 8) and 0xFF
            val blue = pixel and 0xFF
            histogram[(red * 299 + green * 587 + blue * 114) / 1000]++
        }

        fun percentile(fraction: Double): Int {
            val target = (pixels.size * fraction).toInt().coerceIn(0, pixels.size - 1)
            var cumulative = 0
            for (index in histogram.indices) {
                cumulative += histogram[index]
                if (cumulative > target) return index
            }
            return histogram.lastIndex
        }

        val low = percentile(0.10)
        val high = percentile(0.60).coerceAtLeast(low)
        val fadeEnd = (high + 32).coerceAtMost(255)
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var count = 0
        for (pixel in pixels) {
            val pixelRed = (pixel ushr 16) and 0xFF
            val pixelGreen = (pixel ushr 8) and 0xFF
            val pixelBlue = pixel and 0xFF
            val luma = (pixelRed * 299 + pixelGreen * 587 + pixelBlue * 114) / 1000
            if (luma in low..high) {
                red += pixelRed / 255.0
                green += pixelGreen / 255.0
                blue += pixelBlue / 255.0
                count++
            }
        }
        if (count == 0) return BackgroundBalance(floatArrayOf(1f, 1f, 1f), high, fadeEnd)

        val gains = backgroundWhiteBalanceGains(
            (red / count).toFloat(),
            (green / count).toFloat(),
            (blue / count).toFloat()
        )
        return BackgroundBalance(gains, high, fadeEnd)
    }

    internal fun applyBackgroundWhiteBalance(pixels: IntArray, recipe: BackgroundBalance? = null): BackgroundBalance {
        val balance = recipe ?: backgroundBalance(pixels)
        val gains = balance.gains; val high = balance.high; val fadeEnd = balance.fadeEnd
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val sourceRed = (pixel ushr 16) and 0xFF
            val sourceGreen = (pixel ushr 8) and 0xFF
            val sourceBlue = pixel and 0xFF
            val luma = (sourceRed * 299 + sourceGreen * 587 + sourceBlue * 114) / 1000
            val weight = when {
                luma <= high -> 1f
                luma >= fadeEnd -> 0f
                else -> (fadeEnd - luma).toFloat() / (fadeEnd - high).coerceAtLeast(1)
            }
            val mappedRed = (sourceRed * (1f + (gains[0] - 1f) * weight)).toInt().coerceIn(0, 255)
            val mappedGreen = (sourceGreen * (1f + (gains[1] - 1f) * weight)).toInt().coerceIn(0, 255)
            val mappedBlue = (sourceBlue * (1f + (gains[2] - 1f) * weight)).toInt().coerceIn(0, 255)
            pixels[index] = (0xFF shl 24) or (mappedRed shl 16) or (mappedGreen shl 8) or mappedBlue
        }
        return balance
    }

    private fun averageArgbGains(pixels: IntArray): FloatArray {
        val step = (pixels.size / SAMPLE_COUNT).coerceAtLeast(1)
        var red = 0f
        var green = 0f
        var blue = 0f
        var count = 0
        for (index in pixels.indices step step) {
            val pixel = pixels[index]
            red += ((pixel ushr 16) and 0xFF) / 255f
            green += ((pixel ushr 8) and 0xFF) / 255f
            blue += (pixel and 0xFF) / 255f
            count++
        }
        return whiteBalanceGains(red / count, green / count, blue / count)
    }
}
