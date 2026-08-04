package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import kotlin.math.hypot
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
    val background: Float
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

/**
 * Wide-field star finder for phone sky frames (M0 go/no-go).
 * Background: tile median; detections: local maxima above median + k·σ with 3×3 centroid.
 */
object WideFieldStarExtractor {
    private const val DEFAULT_GRID = 32
    private const val SIGMA_K = 5f
    private const val MIN_SEPARATION_PX = 8f
    private const val CENTROID_RADIUS = 2

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
        require(pixels.size >= width * height) { "pixel buffer too small" }
        if (width < gridCellPx * 2 || height < gridCellPx * 2) {
            return StarExtractionResult(emptyList(), gridCellPx, sigmaK, null, 0f, 0f)
        }

        val cell = gridCellPx.coerceIn(16, 128)
        val gridW = (width + cell - 1) / cell
        val gridH = (height + cell - 1) / cell
        val medians = FloatArray(gridW * gridH)
        val scratch = FloatArray(cell * cell)

        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val x0 = gx * cell
                val y0 = gy * cell
                val x1 = min(width, x0 + cell)
                val y1 = min(height, y0 + cell)
                var n = 0
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        scratch[n++] = pixels[row + x]
                    }
                }
                medians[gy * gridW + gx] = medianOf(scratch, n)
            }
        }

        var bgSum = 0.0
        var bgSumSq = 0.0
        var bgN = 0
        val residuals = FloatArray(min(4096, width * height / 64))
        var residualN = 0
        for (y in 0 until height step 8) {
            for (x in 0 until width step 8) {
                val bg = sampleBackground(medians, gridW, gridH, cell, x, y)
                val v = pixels[y * width + x]
                val r = v - bg
                bgSum += bg
                bgSumSq += bg * bg
                bgN++
                if (residualN < residuals.size) {
                    residuals[residualN++] = r
                }
            }
        }
        val backgroundMedian = if (bgN > 0) (bgSum / bgN).toFloat() else 0f
        val sigma = robustSigma(residuals, residualN).coerceAtLeast(1e-3f)
        val thresholdDelta = sigmaK * sigma

        val candidates = ArrayList<ExtractedStar>(256)
        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                val idx = y * width + x
                val v = pixels[idx]
                val bg = sampleBackground(medians, gridW, gridH, cell, x, y)
                if (v < bg + thresholdDelta) continue
                if (v < pixels[idx - 1] || v < pixels[idx + 1] ||
                    v < pixels[idx - width] || v < pixels[idx + width]
                ) {
                    continue
                }
                val (cx, cy, flux) = centroid(pixels, width, height, x, y, bg)
                val snr = ((v - bg) / sigma).coerceAtLeast(0f)
                candidates += ExtractedStar(cx, cy, v, flux, snr, bg)
            }
        }

        candidates.sortByDescending { it.snr }
        val stars = ArrayList<ExtractedStar>(min(maxStars, candidates.size))
        for (c in candidates) {
            if (stars.size >= maxStars) break
            if (stars.any { hypot(it.x - c.x, it.y - c.y) < MIN_SEPARATION_PX }) continue
            stars += c
        }

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
            backgroundMedian = backgroundMedian,
            backgroundSigma = sigma
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

    private fun sampleBackground(
        medians: FloatArray,
        gridW: Int,
        gridH: Int,
        cell: Int,
        x: Int,
        y: Int
    ): Float {
        val gx = (x / cell).coerceIn(0, gridW - 1)
        val gy = (y / cell).coerceIn(0, gridH - 1)
        return medians[gy * gridW + gx]
    }

    private fun centroid(
        pixels: FloatArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        background: Float
    ): Triple<Float, Float, Float> {
        var wSum = 0.0
        var xSum = 0.0
        var ySum = 0.0
        val r = CENTROID_RADIUS
        for (yy in max(0, y - r)..min(height - 1, y + r)) {
            for (xx in max(0, x - r)..min(width - 1, x + r)) {
                val w = (pixels[yy * width + xx] - background).toDouble().coerceAtLeast(0.0)
                wSum += w
                xSum += xx * w
                ySum += yy * w
            }
        }
        if (wSum <= 1e-6) return Triple(x.toFloat(), y.toFloat(), 0f)
        return Triple((xSum / wSum).toFloat(), (ySum / wSum).toFloat(), wSum.toFloat())
    }

    private fun medianOf(values: FloatArray, n: Int): Float {
        if (n <= 0) return 0f
        val copy = values.copyOf(n)
        copy.sort(0, n)
        return if (n % 2 == 1) copy[n / 2] else 0.5f * (copy[n / 2 - 1] + copy[n / 2])
    }

    private fun robustSigma(residuals: FloatArray, n: Int): Float {
        if (n < 8) return 1f
        val copy = residuals.copyOf(n)
        copy.sort(0, n)
        val med = if (n % 2 == 1) copy[n / 2] else 0.5f * (copy[n / 2 - 1] + copy[n / 2])
        for (i in 0 until n) {
            copy[i] = kotlin.math.abs(copy[i] - med)
        }
        copy.sort(0, n)
        val mad = if (n % 2 == 1) copy[n / 2] else 0.5f * (copy[n / 2 - 1] + copy[n / 2])
        return (mad * 1.4826f).coerceAtLeast(1e-3f)
    }
}
