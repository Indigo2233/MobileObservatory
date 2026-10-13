package com.indigo.mobileobservatory.stacking

import kotlin.math.*
import java.util.concurrent.CancellationException

data class DeepSkyConfig(val minimumStars: Int = 6, val maxElongation: Double = 2.2, val minimumTransparency: Double = 0.35, val sigmaThreshold: Double = 5.0, val warmupSamples: Int = 5, val memoryBudgetBytes: Long = 192L * 1024 * 1024)
enum class DeepSkyRejection { TOO_FEW_STARS, TRAILING, CLOUD, REGISTRATION, GEOMETRY, MEMORY_BUDGET, INVALID_PIXELS }
data class DeepSkyStats(val received: Int = 0, val accepted: Int = 0, val rejected: Int = 0, val exposureSeconds: Double = 0.0, val stars: Int = 0, val registrationRmsPx: Double = 0.0, val rotationDegrees: Double = 0.0, val rejectedPixelSamples: Long = 0, val elapsedMs: Long = 0, val estimatedWorkingBytes: Long = 0)
data class DeepSkyFrameResult(val accepted: Boolean, val reason: DeepSkyRejection?, val stats: DeepSkyStats)
data class DeepSkySnapshot(val image: LinearImage, val weights: FloatArray, val stats: DeepSkyStats)

/**
 * Single worker ownership. Snapshot copies are consistent when called by that worker.
 * Cancellation discards this engine's accumulation; live pause lets an in-flight frame finish,
 * while reset/close cancels and discards the engine. Previously returned snapshots stay valid.
 */
class DeepSkyStacker(private val config: DeepSkyConfig = DeepSkyConfig()) {
    private var reference: StarField? = null
    private var width = 0; private var height = 0; private var channels = 0
    private var mean = FloatArray(0); private var m2 = FloatArray(0); private var weights = FloatArray(0)
    var stats = DeepSkyStats(); private set

    fun reset() { reference = null; width = 0; height = 0; channels = 0; mean = FloatArray(0); m2 = FloatArray(0); weights = FloatArray(0); stats = DeepSkyStats() }

    fun add(image: LinearImage, exposureSeconds: Double, cancelled: () -> Boolean = { false }): DeepSkyFrameResult {
        val start = System.nanoTime()
        stats = stats.copy(received = stats.received + 1)
        fun reject(reason: DeepSkyRejection): DeepSkyFrameResult {
            stats = stats.copy(rejected = stats.rejected + 1, elapsedMs = (System.nanoTime() - start) / 1_000_000)
            return DeepSkyFrameResult(false, reason, stats)
        }
        if (image.pixels.any { !it.isFinite() } || !exposureSeconds.isFinite() || exposureSeconds < 0) return reject(DeepSkyRejection.INVALID_PIXELS)
        if (reference != null && (image.width != width || image.height != height || image.channels != channels)) return reject(DeepSkyRejection.GEOMETRY)
        // mean, M2, current, bounded raw queue, immutable snapshot, background and processed
        // preview, plus mono detail filters/weights/masks. Fail before allocating accumulation.
        val workingBytes = estimateWorkingBytes(image.width, image.height, image.channels)
        if (workingBytes > config.memoryBudgetBytes) return reject(DeepSkyRejection.MEMORY_BUDGET)
        val field = StarRegistration.detect(image, cancelled = cancelled)
        stats = stats.copy(stars = field.stars.size, estimatedWorkingBytes = workingBytes)
        if (field.stars.size < config.minimumStars) return reject(DeepSkyRejection.TOO_FEW_STARS)
        if (field.medianElongation > config.maxElongation) return reject(DeepSkyRejection.TRAILING)
        val ref = reference
        val registration = if (ref != null) StarRegistration.match(ref, field, cancelled = cancelled) ?: return reject(DeepSkyRejection.REGISTRATION) else StarMatch(RigidTransform(), field.stars.size, 0.0, 1.0)
        if (ref != null && (registration.transparency < config.minimumTransparency || field.stars.size < ref.stars.size * 0.25 || field.background > ref.background + max(0.08, ref.noise * 20))) return reject(DeepSkyRejection.CLOUD)
        if (ref == null) {
            width = image.width; height = image.height; channels = image.channels
            mean = FloatArray(image.pixels.size); m2 = FloatArray(image.pixels.size); weights = FloatArray(width * height)
            reference = field
        }
        val t = registration.transform; val c = t.cosine; val s = t.sine
        var rejectedPixels = 0L
        val sampled = FloatArray(channels)
        for (y in 0 until height) for (x in 0 until width) {
            if (x == 0 && y % 16 == 0 && cancelled()) {
                reset()
                throw CancellationException("Stack generation discarded")
            }
            // Inverse rigid warp. Invalid support never receives a weight, including transformed edges.
            val sx = c * (x - t.dx) + s * (y - t.dy); val sy = -s * (x - t.dx) + c * (y - t.dy)
            if (sx < -1e-7 || sy < -1e-7 || sx > width - 1.0 + 1e-7 || sy > height - 1.0 + 1e-7) continue
            val fx = sx.coerceIn(0.0, width - 1.0); val fy = sy.coerceIn(0.0, height - 1.0)
            val ix = floor(fx).toInt(); val iy = floor(fy).toInt(); val ix1 = min(ix + 1, width - 1); val iy1 = min(iy + 1, height - 1)
            val ax = fx - ix; val ay = fy - iy
            val p = y * width + x; val weight = weights[p]
            var reject = false
            for (ch in 0 until channels) {
                val v00 = image.pixels[(iy * width + ix) * channels + ch]; val v10 = image.pixels[(iy * width + ix1) * channels + ch]
                val v01 = image.pixels[(iy1 * width + ix) * channels + ch]; val v11 = image.pixels[(iy1 * width + ix1) * channels + ch]
                val value = ((v00 * (1 - ax) + v10 * ax) * (1 - ay) + (v01 * (1 - ax) + v11 * ax) * ay).toFloat()
                sampled[ch] = value
                if (weight >= config.warmupSamples) {
                    val i = p * channels + ch
                    // Local range allows interpolation variations on stellar cores without clipping signal.
                    // Derive allowance from the accumulated scene, never the incoming frame:
                    // an incoming hot pixel must not inflate its own rejection threshold.
                    var localLow = mean[i]; var localHigh = mean[i]
                    for (oy in -1..1) for (ox in -1..1) {
                        val neighbor = ((y + oy).coerceIn(0,height-1) * width + (x + ox).coerceIn(0,width-1)) * channels + ch
                        localLow = min(localLow,mean[neighbor]); localHigh = max(localHigh,mean[neighbor])
                    }
                    val gradientAllowance = (localHigh - localLow) * 0.35
                    val sigma = max(field.noise, sqrt(max(0.0, m2[i].toDouble() / max(1f, weight - 1))))
                    if (abs(value - mean[i]) > config.sigmaThreshold * sigma + gradientAllowance + 1e-5) reject = true
                }
            }
            if (reject) { rejectedPixels++; continue }
            val next = weight + 1
            for (ch in 0 until channels) {
                val i = p * channels + ch; val delta = sampled[ch] - mean[i]
                mean[i] += delta / next; m2[i] += delta * (sampled[ch] - mean[i])
            }
            weights[p] = next
        }
        stats = stats.copy(accepted = stats.accepted + 1, exposureSeconds = stats.exposureSeconds + exposureSeconds,
            registrationRmsPx = registration.rmsPixels, rotationDegrees = Math.toDegrees(atan2(s,c)),
            rejectedPixelSamples = stats.rejectedPixelSamples + rejectedPixels, elapsedMs = (System.nanoTime() - start) / 1_000_000)
        return DeepSkyFrameResult(true, null, stats)
    }

    fun snapshot(): DeepSkySnapshot? = if (stats.accepted == 0) null else DeepSkySnapshot(LinearImage(width, height, channels, mean.copyOf()), weights.copyOf(), stats)

    companion object {
        // A slow renderer may retain snapshot A while the latest result is B and the
        // science worker allocates C. Budget all three transient image/weight pairs,
        // the bounded raw queue and decoder, accumulators, automatic background output,
        // and simultaneous sharpening/local-contrast scratch. Calibration is added by
        // the coordinator because its masters and sensor geometry can differ after binning.
        fun estimateWorkingBytes(width: Int, height: Int, channels: Int): Long = width.toLong() * height * (channels * 40L + 32L) + 8L * 1024 * 1024
        const val REJECTION_LIMITATION = "Temporal sigma rejection starts after 5 valid samples per pixel. Early outliers and persistent hot pixels are not guaranteed to be removed; use dark calibration/dithering for persistent defects. Uncalibrated by default."
    }
}
