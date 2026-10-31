package com.indigo.mobileobservatory.stacking

import java.util.concurrent.CancellationException
import kotlin.math.asinh
import kotlin.math.max
import kotlin.math.min

/** A bounded display-only estimate. The returned ordinary recipe completely defines the result. */
object StackAutoStretch {
    private const val MAX_SAMPLES = 16_384

    /**
     * Samples display-corrected linear luminance, excluding uncovered pixels. The outer 0.5% tails do
     * not set the range, so sparse hot pixels/saturated stars cannot suppress a dark target.
     * No image-sized scratch or data mutation; constant/invalid input has no useful estimate.
     * WB, saturation, background and detail controls survive unchanged. Statistics use the
     * same corrected and balanced linear samples as rendering. Explicit use from RAW
     * selects NATURAL because RAW deliberately bypasses all display processing.
     */
    fun estimate(
        linear: LinearImage,
        current: StackProcessingParameters,
        weights: FloatArray? = null,
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
        val low = percentile(.005)
        val median = percentile(.5)
        val high = percentile(.995)
        val spread = high - low
        if (spread <= max(1e-7, kotlin.math.abs(median) * 1e-6) || high <= 0.0 || low >= 1.0) return null

        // This robust white sets the display knee. The shoulder retains brighter signal.
        val black = max(0.0, low - spread * .05).coerceAtMost(.99)
        val white = min(1.0, max(high + spread * .25, black + .001))
        if (white <= black || median <= black || median >= white) return null
        val curve = 4.0
        val normalizedMedian = ((median - black) / (white - black)).coerceIn(0.0, 1.0)
        val curved = asinh(curve * normalizedMedian) / asinh(curve)
        val x = if (current.displayVersion >= 2) compressHighlights(curved,current.highlightCompression) else curved
        // Invert the midtone transfer after the selected shoulder for a moderate background.
        val target = .12
        val midtone = (x * (1 - target) / (x + target - 2 * x * target)).coerceIn(.01, .99)
        return current.copy(
            preset = if (current.preset == ProcessingPreset.RAW) ProcessingPreset.NATURAL else current.preset,
            blackPoint = black, whitePoint = white, midtone = midtone,
            curve = curve, brightness = 1.0, stretchEnabled = true
        )
    }
}
