package com.indigo.mobileobservatory.stacking

import java.util.concurrent.CancellationException
import kotlin.math.asinh
import kotlin.math.max
import kotlin.math.min

/** A bounded display-only estimate. The returned ordinary recipe completely defines the result. */
object StackAutoStretch {
    private const val MAX_SAMPLES = 16_384
    private const val SHADOWS_CLIP_SIGMA = 2.8
    private const val TARGET_BACKGROUND = 0.25

    /**
     * Samples display-corrected linear luminance, excluding uncovered pixels. Robust tails and MAD
     * statistics keep sparse hot pixels and saturated stars from suppressing a dark target.
     * No image-sized scratch or data mutation; constant/invalid input has no useful estimate.
     * WB, saturation, background and detail controls survive unchanged. Statistics use the
     * same corrected and balanced linear samples as rendering. Explicit use from RAW
     * selects NATURAL because RAW deliberately bypasses all display processing.
     */
    fun estimate(
        linear: LinearImage,
        current: StackProcessingParameters,
        weights: PixelWeights? = null,
        backgroundCache: BackgroundModelCache? = null,
        cancelled: () -> Boolean = { false }
    ): StackProcessingParameters? {
        require(weights == null || weights.size == linear.pixelCount)
        val source = StackPostProcessor.displaySource(linear,current,backgroundCache,cancelled)
        val gains = StackPostProcessor.whiteBalance(source,current,cancelled)
        val count = min(MAX_SAMPLES, linear.pixelCount)
        val samples = DoubleArray(count)
        var valid = 0
        var invalid = 0
        for (i in 0 until count) {
            if (i % 1024 == 0 && cancelled()) throw CancellationException("Auto stretch cancelled")
            val pixel = ((i.toLong() * linear.pixelCount + linear.pixelCount / 2) / count).toInt()
                .coerceAtMost(linear.pixelCount - 1)
            if (weights != null && (!weights[pixel].isFinite() || weights[pixel] <= 0f)) continue
            val luminance = StackPostProcessor.balancedLuminance(source,gains,pixel)
            if (!luminance.isFinite()) { invalid++; continue }
            samples[valid++] = luminance
        }
        if (cancelled()) throw CancellationException("Auto stretch cancelled")
        if (valid < 32 || invalid > 0) return null
        samples.sort(0, valid)
        fun percentile(fraction: Double): Double = samples[((valid - 1) * fraction).toInt()]
        val median = percentile(.5)
        val deviations = DoubleArray(valid)
        for (i in 0 until valid) deviations[i] = kotlin.math.abs(samples[i] - median)
        deviations.sort()
        val sigma = 1.4826 * deviations[valid / 2]
        val lowTail = percentile(.001)
        val high = percentile(.999)
        val spread = high - lowTail
        if (spread <= max(1e-7, kotlin.math.abs(median) * 1e-6) || high <= 0.0 || lowTail >= 1.0) return null

        // Siril's documented autostretch reference uses -2.8 sigma from the main histogram
        // peak. MAD makes that peak insensitive to nebula and sparse stars.
        val black = max(lowTail, median - SHADOWS_CLIP_SIGMA * sigma).coerceIn(0.0, .99)
        val white = max(high + max(sigma * .5, spread * .04), black + .001)
        if (white <= black || median <= black || median >= white) return null
        val normalizedSpan = (high - lowTail) / max(1e-9, median - black)
        val curve = (3.5 + (2.5 / max(1.0, normalizedSpan))).coerceIn(3.5, 6.0)
        val normalizedMedian = ((median - black) / (white - black)).coerceIn(0.0, 1.0)
        val curved = asinh(curve * normalizedMedian) / asinh(curve)
        val x = if (current.displayVersion >= 2) compressHighlights(curved,current.highlightCompression) else curved
        // Use Siril's default target background for the current multiscale renderer and retain
        // the darker legacy target for replay compatibility.
        val target = if (current.displayVersion >= 3) TARGET_BACKGROUND else .12
        val midtone = (x * (1 - target) / (x + target - 2 * x * target)).coerceIn(.01, .99)
        val multiscaleWhite = if (current.displayVersion >= 3)
            white + max(sigma * 4.0, spread * .18) else white
        return current.copy(
            preset = if (current.preset == ProcessingPreset.RAW) ProcessingPreset.NATURAL else current.preset,
            blackPoint = black, whitePoint = multiscaleWhite, midtone = midtone,
            curve = curve, brightness = 1.0, stretchEnabled = true
        )
    }
}
