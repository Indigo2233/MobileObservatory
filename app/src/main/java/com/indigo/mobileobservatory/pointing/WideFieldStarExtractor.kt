package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ExtractedStar(
    val x: Float,
    val y: Float,
    val peak: Float,
    val flux: Float,
    val snr: Float,
    val background: Float,
    /** Minor/major axis of the detection window; 1 is round, ~0 is a trail or spike. */
    val roundness: Float = 1f,
    /** Mean Gaussian-like width in pixels. */
    val sizePx: Float = 2f
)

data class StarExtractionResult(
    val stars: List<ExtractedStar>,
    val gridCellPx: Int,
    val sigmaThreshold: Float,
    /** Rough completeness magnitude for this FOV from star density (uncalibrated photometry). */
    val estimatedLimitingMagnitude: Float?,
    val backgroundMedian: Float,
    val backgroundSigma: Float
)

/** Finds compact point sources in sensor-native pixel coordinates (integer pixel centers). */
object WideFieldStarExtractor {
    private const val DEFAULT_GRID = 32
    private const val SIGMA_K = 5f
    private const val MIN_SEPARATION_PX = 8f
    private const val BACKGROUND_RADIUS = 4
    private const val STAR_RADIUS = BACKGROUND_RADIUS - 1
    // The phone's averaged RAW and YUV values are quantized to whole ADU.
    private const val MINIMUM_NOISE_ADU = 1f
    private const val MINIMUM_ROUNDNESS = 0.38f
    private const val MINIMUM_SIZE_PX = 1.15f
    private const val MAXIMUM_SIZE_PX = 4.5f
    private const val SPREAD_COLUMNS = 4
    private const val SPREAD_ROWS = 3

    fun extract(
        pixels: FloatArray,
        width: Int,
        height: Int,
        maxStars: Int = 200,
        gridCellPx: Int = DEFAULT_GRID,
        sigmaK: Float = SIGMA_K,
        fovWidthDeg: Double? = null,
        fovHeightDeg: Double? = null
    ): StarExtractionResult {
        require(width > 0 && height > 0 && width.toLong() * height <= pixels.size) {
            "Invalid image dimensions or pixel buffer"
        }
        val cell = gridCellPx.coerceIn(16, 128)
        if (width <= 2 * BACKGROUND_RADIUS || height <= 2 * BACKGROUND_RADIUS) {
            return StarExtractionResult(emptyList(), cell, sigmaK, null, 0f, 0f)
        }

        val gridW = (width + cell - 1) / cell
        val gridH = (height + cell - 1) / cell
        val noise = FloatArray(gridW * gridH)
        val backgrounds = FloatArray(noise.size)
        val scratch = FloatArray(cell * cell)
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val cellIndex = gy * gridW + gx
                val x0 = gx * cell
                val y0 = gy * cell
                val x1 = min(width, x0 + cell)
                val y1 = min(height, y0 + cell)
                var n = 0
                for (y in y0 until y1 step 3) {
                    for (x in x0 until x1 step 3) scratch[n++] = pixels[y * width + x]
                }
                backgrounds[cellIndex] = medianOf(scratch, n)
                n = 0
                for (y in y0 until y1 step 3) {
                    val row = y * width
                    for (x in max(2, x0) until min(width - 2, x1) step 3) {
                        // A symmetric difference removes first-order sky gradients.
                        scratch[n++] = abs(pixels[row + x] - (pixels[row + x - 2] + pixels[row + x + 2]) / 2)
                    }
                }
                // Convert median absolute deviation of the difference to pixel noise.
                noise[cellIndex] = (medianOf(scratch, n) * (1.4826f / sqrt(1.5f))).coerceAtLeast(MINIMUM_NOISE_ADU)
            }
        }

        val radius = BACKGROUND_RADIUS
        val candidates = ArrayList<ExtractedStar>(256)
        for (y in radius until height - radius) {
            val gridRow = y / cell * gridW
            for (x in radius until width - radius) {
                val idx = y * width + x
                val peak = pixels[idx]
                // Break equal-value plateaus consistently in scan order.
                if (peak <= pixels[idx - 1] || peak < pixels[idx + 1] ||
                    peak <= pixels[idx - width] || peak < pixels[idx + width] ||
                    peak <= pixels[idx - width - 1] || peak <= pixels[idx - width + 1] ||
                    peak < pixels[idx + width - 1] || peak < pixels[idx + width + 1]
                ) continue
                val sigma = noise[gridRow + x / cell]
                val axialBackground = (pixels[idx - radius] + pixels[idx + radius] +
                    pixels[idx - radius * width] + pixels[idx + radius * width]) / 4
                if (peak - axialBackground < 2 * sigma) continue
                measureStar(pixels, width, x, y, sigma, sigmaK)?.let(candidates::add)
            }
        }

        candidates.sortByDescending { it.snr }
        val stars = MatchStarSelector.spread(
            stars = candidates,
            limit = maxStars,
            minSeparationPx = MIN_SEPARATION_PX,
            columns = SPREAD_COLUMNS,
            rows = SPREAD_ROWS
        )

        val limiting = if (fovWidthDeg != null && fovHeightDeg != null && stars.isNotEmpty()) {
            estimateLimitingMagnitude(stars.size, fovWidthDeg, fovHeightDeg)
        } else {
            null
        }

        return StarExtractionResult(
            stars = stars,
            gridCellPx = cell,
            sigmaThreshold = sigmaK,
            estimatedLimitingMagnitude = limiting,
            backgroundMedian = medianOf(backgrounds, backgrounds.size),
            backgroundSigma = medianOf(noise, noise.size)
        )
    }

    fun extractFromFrame(
        frame: FrameData,
        maxStars: Int = 200,
        fovWidthDeg: Double? = null,
        fovHeightDeg: Double? = null
    ): StarExtractionResult {
        val pixels = frameToFloatPixels(frame)
        return extract(
            pixels = pixels,
            width = frame.width,
            height = frame.height,
            maxStars = maxStars,
            fovWidthDeg = fovWidthDeg,
            fovHeightDeg = fovHeightDeg
        )
    }

    fun frameToFloatPixels(frame: FrameData): FloatArray {
        val w = frame.width
        val h = frame.height
        val out = FloatArray(w * h)
        val bpp = frame.pixelFormat.bytesPerPixel
        val data = frame.data
        when {
            frame.pixelFormat == PixelFormat.MONO8 || bpp == 1 -> {
                for (i in 0 until w * h) {
                    out[i] = (data[i].toInt() and 0xFF).toFloat()
                }
            }
            bpp >= 2 -> {
                for (i in 0 until w * h) {
                    val o = i * bpp
                    val lo = data[o].toInt() and 0xFF
                    val hi = data[o + 1].toInt() and 0xFF
                    out[i] = (lo or (hi shl 8)).toFloat()
                }
            }
            else -> error("unsupported pixel format ${frame.pixelFormat}")
        }
        return out
    }

    /**
     * Invert all-sky cumulative star counts (bright end approximation) for this FOV.
     * N(<m) ≈ 10^(0.517·m − 1.52) over whole sky for m ≲ 8 (Allen / Bahcall-style fit).
     */
    fun estimateLimitingMagnitude(starCount: Int, fovWidthDeg: Double, fovHeightDeg: Double): Float {
        if (starCount <= 0) return Float.NEGATIVE_INFINITY
        val areaSqDeg = (fovWidthDeg * fovHeightDeg).coerceAtLeast(1e-3)
        val allSky = 41252.96
        val fraction = (areaSqDeg / allSky).coerceIn(1e-6, 1.0)
        val allSkyCount = starCount / fraction
        // log10(N) ≈ 0.517 m - 1.52  =>  m ≈ (log10(N) + 1.52) / 0.517
        val m = ((log10(allSkyCount.coerceAtLeast(1.0)) + 1.52) / 0.517).toFloat()
        return m.coerceIn(0f, 12f)
    }

    private fun measureStar(
        pixels: FloatArray,
        width: Int,
        x: Int,
        y: Int,
        tileSigma: Float,
        sigmaK: Float
    ): ExtractedStar? {
        val radius = BACKGROUND_RADIUS
        var sum = 0.0
        var sumSquared = 0.0
        var xSum = 0.0
        var ySum = 0.0
        for (dy in -radius..radius) {
            val row = (y + dy) * width + x
            val step = if (abs(dy) == radius) 1 else 2 * radius
            for (dx in -radius..radius step step) {
                val value = pixels[row + dx].toDouble()
                sum += value
                sumSquared += value * value
                xSum += dx * value
                ySum += dy * value
            }
        }
        // Fit a tilted background plane to the square perimeter. Its residual also
        // raises the threshold near structured backgrounds such as text and foliage.
        val n = 8 * radius
        val axisSumSquared = 2 * radius * radius * (2 * radius + 1) +
            2 * (radius - 1) * radius * (2 * radius - 1) / 3
        val background = sum / n
        val slopeX = xSum / axisSumSquared
        val slopeY = ySum / axisSumSquared
        val variance = (sumSquared - sum * background - xSum * slopeX - ySum * slopeY) / n
        val sigma = max(tileSigma.toDouble(), sqrt(variance.coerceAtLeast(0.0)))
        val peak = pixels[y * width + x]
        val contrast = peak - background
        if (contrast < sigmaK * sigma) return null

        val minSignal = max(sigma, contrast * 0.08)
        var flux = 0.0
        var mx = 0.0
        var my = 0.0
        var mxx = 0.0
        var myy = 0.0
        var mxy = 0.0
        for (dy in -STAR_RADIUS..STAR_RADIUS) {
            val row = (y + dy) * width + x
            for (dx in -STAR_RADIUS..STAR_RADIUS) {
                val signal = pixels[row + dx] - (background + slopeX * dx + slopeY * dy)
                if (signal < minSignal) continue
                flux += signal
                mx += dx * signal
                my += dy * signal
                mxx += dx * dx * signal
                myy += dy * dy * signal
                mxy += dx * dy * signal
            }
        }
        if (flux <= 0.0) return null
        val cx = mx / flux
        val cy = my / flux
        val xx = mxx / flux - cx * cx
        val yy = myy / flux - cy * cy
        val xy = mxy / flux - cx * cy
        val disc = sqrt((xx - yy) * (xx - yy) + 4.0 * xy * xy)
        val major = sqrt(2 * (xx + yy + disc).coerceAtLeast(0.0))
        val minor = sqrt(2 * (xx + yy - disc).coerceAtLeast(0.0))
        val size = ((major + minor) / 2).toFloat()
        if (size !in MINIMUM_SIZE_PX..MAXIMUM_SIZE_PX) return null
        val roundness = (minor / major).toFloat()
        if (roundness < MINIMUM_ROUNDNESS) return null
        return ExtractedStar((x + cx).toFloat(), (y + cy).toFloat(), peak, flux.toFloat(),
            (contrast / sigma).toFloat(), background.toFloat(), roundness, size)
    }

    private fun medianOf(values: FloatArray, n: Int): Float {
        if (n == 0) return 0f
        values.sort(0, n)
        return if (n % 2 == 1) values[n / 2] else 0.5f * (values[n / 2 - 1] + values[n / 2])
    }
}
