package com.indigo.mobileobservatory.stacking

import kotlin.math.*

/** Coordinates are in the original, unscaled, linear image. */
data class PixelRoi(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    fun checked(imageWidth: Int, imageHeight: Int): PixelRoi {
        require(left >= 0 && top >= 0 && width >= 12 && height >= 12 &&
            left.toLong() + width <= imageWidth && top.toLong() + height <= imageHeight) {
            "Target ROI must be at least 12 × 12 pixels and inside the image"
        }
        return this
    }
}

enum class PlanetaryRejection { NONE, LOW_SIGNAL, SATURATION, OUT_OF_FRAME, NOISE, GLOBAL_MATCH, LOCAL_MATCH, FOLDED_WARP, EDGE_MISMATCH }

data class PlanetaryQuality(
    val score: Double,
    val sharpness: Double,
    val noise: Double,
    val contrast: Double,
    val targetFraction: Double,
    val rejection: PlanetaryRejection
)

/**
 * Original implementation following AS's noise-robust gradient/edge quality and target ROI principles.
 * Reference: https://www.astrokraai.nl/software/manual/as2_planet.html
 * AS code is not used. This implementation ranks whole frames, not separate selections per AP.
 * A smoothed gradient removes single-pixel noise/hot pixels; a Laplacian MAD estimates noise.
 */
object PlanetaryQualityEstimator {
    fun measure(image: LinearImage, roi: PixelRoi, checkCancelled: () -> Unit = {}): PlanetaryQuality {
        roi.checked(image.width, image.height)
        val histogram = IntArray(1024)
        val noiseHistogram = IntArray(1024)
        val stride = max(1, sqrt(roi.width.toDouble() * roi.height / 200_000.0).toInt())
        var count = 0
        var saturated = 0
        for (y in roi.top + 2 until roi.bottom - 2 step stride) {
            checkCancelled()
            for (x in roi.left + 2 until roi.right - 2 step stride) {
                val v = PlanetarySampling.luma(image, x, y)
                if (!v.isFinite()) continue
                histogram[(v * 1023).toInt().coerceIn(0, 1023)]++
                if (v >= 0.995f) saturated++
                val lap = 4 * v - PlanetarySampling.luma(image, x - 1, y) - PlanetarySampling.luma(image, x + 1, y) -
                    PlanetarySampling.luma(image, x, y - 1) - PlanetarySampling.luma(image, x, y + 1)
                noiseHistogram[(abs(lap) * 1023).toInt().coerceIn(0, 1023)]++
                count++
            }
        }
        fun quantile(hist: IntArray, fraction: Double): Double {
            var n = 0
            val target = max(1, ceil(count * fraction).toInt())
            for (i in hist.indices) { n += hist[i]; if (n >= target) return i / 1023.0 }
            return 0.0
        }
        val background = quantile(histogram, 0.1)
        val high = quantile(histogram, 0.98)
        val contrast = high - background
        // sigma of five-point Laplacian = sqrt(20) sigma, median |normal| = .67449 sigma.
        val noise = quantile(noiseHistogram, 0.5) / 3.016
        val threshold = background + max(0.006, max(contrast * 0.12, noise * 4))
        var target = 0
        var gradient = 0.0
        var borderSignal = 0
        var borderCount = 0
        for (y in roi.top + 2 until roi.bottom - 2 step stride) {
            checkCancelled()
            for (x in roi.left + 2 until roi.right - 2 step stride) {
                val value = PlanetarySampling.smooth(image, x, y)
                if (!value.isFinite() || value <= threshold || value >= .995) continue
                val gx = (PlanetarySampling.smooth(image, x + 1, y) - PlanetarySampling.smooth(image, x - 1, y)) * .5
                val gy = (PlanetarySampling.smooth(image, x, y + 1) - PlanetarySampling.smooth(image, x, y - 1)) * .5
                if (!gx.isFinite() || !gy.isFinite()) continue
                gradient += max(0.0, gx * gx + gy * gy - noise * noise * .15)
                target++
            }
        }
        // Check physical sensor boundaries, not the requested ROI boundary (a cropped lunar ROI is legitimate).
        val edgeStep = max(1, min(image.width, image.height) / 512)
        for (x in 1 until image.width - 1 step edgeStep) {
            for (y in intArrayOf(1, image.height - 2)) {
                if (x >= roi.left && x < roi.right && y >= roi.top && y < roi.bottom) {
                    borderCount++; if (PlanetarySampling.luma(image, x, y) > threshold) borderSignal++
                }
            }
        }
        for (y in 1 until image.height - 1 step edgeStep) {
            for (x in intArrayOf(1, image.width - 2)) {
                if (x >= roi.left && x < roi.right && y >= roi.top && y < roi.bottom) {
                    borderCount++; if (PlanetarySampling.luma(image, x, y) > threshold) borderSignal++
                }
            }
        }
        val fraction = target.toDouble() / max(1, count)
        val sharpness = sqrt(gradient / max(1, target))
        val snr = contrast / max(0.001, noise)
        val rejection = when {
            saturated > count * .25 -> PlanetaryRejection.SATURATION
            count < 32 || contrast < 0.008 -> PlanetaryRejection.LOW_SIGNAL
            snr < 5.0 -> PlanetaryRejection.NOISE
            target < 20 || sharpness < .00015 -> PlanetaryRejection.LOW_SIGNAL
            // A surface filling the entire ROI is supported; reject partially clipped isolated disks.
            borderCount > 0 && borderSignal > borderCount * .04 && fraction < .65 -> PlanetaryRejection.OUT_OF_FRAME
            else -> PlanetaryRejection.NONE
        }
        // Absolute sharpness rewards true contrast, normalized term rewards edge/detail resolution.
        val score = (sharpness / max(.02, contrast) * (snr / (snr + 8)) * sqrt(contrast) * 10).coerceIn(0.0, 1.0)
        return PlanetaryQuality(if (rejection == PlanetaryRejection.NONE) score else 0.0, sharpness, noise, contrast, fraction, rejection)
    }
}

internal object PlanetarySampling {
    fun luma(image: LinearImage, x: Int, y: Int): Float {
        val p = (y * image.width + x) * image.channels
        return if (image.channels == 1) image.pixels[p] else
            image.pixels[p] * .2126f + image.pixels[p + 1] * .7152f + image.pixels[p + 2] * .0722f
    }
    fun smooth(image: LinearImage, x: Int, y: Int): Double =
        (luma(image, x, y) * 4 + luma(image, x - 1, y) * 2 + luma(image, x + 1, y) * 2 +
            luma(image, x, y - 1) * 2 + luma(image, x, y + 1) * 2 +
            luma(image, x - 1, y - 1) + luma(image, x + 1, y - 1) +
            luma(image, x - 1, y + 1) + luma(image, x + 1, y + 1)) / 16.0

    fun plane(image: LinearImage, checkCancelled: () -> Unit): FloatArray {
        val out = FloatArray(image.width * image.height)
        fillPlane(image, out, checkCancelled)
        return out
    }
    fun fillPlane(image: LinearImage, out: FloatArray, checkCancelled: () -> Unit) {
        require(out.size == image.width * image.height)
        for (y in 0 until image.height) {
            checkCancelled()
            for (x in 0 until image.width) out[y * image.width + x] = luma(image, x, y)
        }
    }
    fun bilinear(p: FloatArray, width: Int, height: Int, x: Double, y: Double): Double {
        if (x < 0 || y < 0 || x > width - 1 || y > height - 1) return Double.NaN
        val ix = x.toInt(); val iy = y.toInt()
        val nx = min(width - 1, ix + 1); val ny = min(height - 1, iy + 1)
        val fx = x - ix; val fy = y - iy
        return (p[iy * width + ix] * (1 - fx) + p[iy * width + nx] * fx) * (1 - fy) +
            (p[ny * width + ix] * (1 - fx) + p[ny * width + nx] * fx) * fy
    }
}
