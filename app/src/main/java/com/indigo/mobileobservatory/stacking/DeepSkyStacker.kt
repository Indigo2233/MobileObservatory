package com.indigo.mobileobservatory.stacking

import kotlin.math.*
import java.util.concurrent.CancellationException

data class DeepSkyConfig(val minimumStars: Int = 6, val maxElongation: Double = 2.2, val minimumTransparency: Double = 0.35, val sigmaThreshold: Double = 5.0, val warmupSamples: Int = 5, val memoryBudgetBytes: Long = 192L * 1024 * 1024)
enum class DeepSkyRejection { TOO_FEW_STARS, TRAILING, CLOUD, REGISTRATION, GEOMETRY, MEMORY_BUDGET, INVALID_PIXELS, FRAME_LIMIT }
data class DeepSkyStats(
    val received: Int = 0, val accepted: Int = 0, val rejected: Int = 0, val exposureSeconds: Double = 0.0,
    val stars: Int = 0, val registrationRmsPx: Double = 0.0, val rotationDegrees: Double = 0.0,
    val rejectedPixelSamples: Long = 0, val elapsedMs: Long = 0, val estimatedWorkingBytes: Long = 0,
    val inputEncoding: String = "float32", val maximumInputQuantizationError: Double = 0.0
)
data class DeepSkyFrameResult(val accepted: Boolean, val reason: DeepSkyRejection?, val stats: DeepSkyStats)
data class DeepSkySnapshot(val image: LinearImage, val weights: PixelWeights, val stats: DeepSkyStats)

/**
 * Single worker ownership. Snapshot copies are consistent when called by that worker.
 * Cancellation discards this engine's accumulation; live pause lets an in-flight frame finish,
 * while reset/close cancels and discards the engine. Previously returned snapshots stay valid.
 */
class DeepSkyStacker(private val config: DeepSkyConfig = DeepSkyConfig(), private val livePreviewOverlap: Boolean = true,
                     private val reusableOfflineInput: Boolean = false, private val livePreviewMaxSide: Int? = null) {
    private val starDetectionWorkspace = StarRegistration.Workspace()
    private var reference: StarField? = null
    private var width = 0; private var height = 0; private var channels = 0
    private var native: NativeStackAccumulator? = null
    private var mean = FloatArray(0); private var m2 = FloatArray(0); private var weights: CoverageBuffer? = null
    var stats = DeepSkyStats(); private set

    fun reset() {
        reference = null; width = 0; height = 0; channels = 0
        native?.close(); native = null
        mean = FloatArray(0); m2 = FloatArray(0); weights = null
        starDetectionWorkspace.release()
        stats = DeepSkyStats()
    }

    fun add(image: LinearSamples, exposureSeconds: Double, cancelled: () -> Boolean = { false }): DeepSkyFrameResult {
        val start = System.nanoTime()
        stats = stats.copy(received = stats.received + 1)
        fun reject(reason: DeepSkyRejection): DeepSkyFrameResult {
            stats = stats.copy(rejected = stats.rejected + 1, elapsedMs = (System.nanoTime() - start) / 1_000_000)
            return DeepSkyFrameResult(false, reason, stats)
        }
        if (!image.allFinite() || !exposureSeconds.isFinite() || exposureSeconds < 0) return reject(DeepSkyRejection.INVALID_PIXELS)
        if (stats.accepted >= MAX_STACK_FRAMES) return reject(DeepSkyRejection.FRAME_LIMIT)
        if (reference != null && (image.width != width || image.height != height || image.channels != channels)) return reject(DeepSkyRejection.GEOMETRY)
        // Admission precedes accumulation. Offline rendering starts after the engine releases
        // its workspace; live decoding/accumulation and immutable previews can overlap.
        val workingBytes = if (livePreviewOverlap) estimateWorkingBytes(image.width, image.height, image.channels,
            livePreviewMaxSide, image.storageBytes)
            else estimateOfflineWorkingBytes(image.width, image.height, image.channels, reusableOfflineInput)
        if (workingBytes > config.memoryBudgetBytes) return reject(DeepSkyRejection.MEMORY_BUDGET)
        val field = StarRegistration.detect(image, workspace = starDetectionWorkspace, cancelled = cancelled)
        stats = stats.copy(stars = field.stars.size, estimatedWorkingBytes = workingBytes)
        if (field.stars.size < config.minimumStars) return reject(DeepSkyRejection.TOO_FEW_STARS)
        if (field.medianElongation > config.maxElongation) return reject(DeepSkyRejection.TRAILING)
        val ref = reference
        val registration = if (ref != null) StarRegistration.match(ref, field, cancelled = cancelled) ?: return reject(DeepSkyRejection.REGISTRATION) else StarMatch(RigidTransform(), field.stars.size, 0.0, 1.0)
        if (ref != null && (registration.transparency < config.minimumTransparency || field.stars.size < ref.stars.size * 0.25 || field.background > ref.background + max(0.08, ref.noise * 20))) return reject(DeepSkyRejection.CLOUD)
        if (ref == null) {
            width = image.width; height = image.height; channels = image.channels
            if (image is LinearImage) native = NativeStackAccumulator.create(width, height, channels)
            if (native == null) {
                mean = FloatArray(image.pixelCount * channels)
                m2 = FloatArray(image.pixelCount * channels)
                weights = CoverageBuffer(ShortArray(width * height))
            }
            reference = field
        }
        val nativeAccumulator = native
        if (nativeAccumulator != null && image is LinearImage) {
            val rejectedPixels = try {
                nativeAccumulator.accumulate(image, registration.transform, config,
                    field.noise.toDouble(), StackCancelCheck { cancelled() })
            } catch (_: CoverageLimitException) {
                return reject(DeepSkyRejection.FRAME_LIMIT)
            } catch (_: CancellationException) {
                reset()
                throw CancellationException("Stack generation discarded")
            }
            stats = stats.copy(accepted = stats.accepted + 1, exposureSeconds = stats.exposureSeconds + exposureSeconds,
                registrationRmsPx = registration.rmsPixels,
                rotationDegrees = Math.toDegrees(atan2(registration.transform.sine, registration.transform.cosine)),
                rejectedPixelSamples = stats.rejectedPixelSamples + rejectedPixels,
                elapsedMs = (System.nanoTime() - start) / 1_000_000,
                inputEncoding = image.encoding,
                maximumInputQuantizationError = max(stats.maximumInputQuantizationError, image.maximumQuantizationError))
            return DeepSkyFrameResult(true, null, stats)
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
            val p = y * width + x; val weight = weights!![p]
            var reject = false
            image.sampleBilinear(ix, iy, ix1, iy1, ax, ay, sampled)
            for (ch in 0 until channels) {
                val value = sampled[ch]
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
                    val variance = m2[i].toDouble() / max(1, weight - 1).toDouble()
                    val sigma = max(field.noise, sqrt(max(0.0, variance)))
                    if (abs(value - mean[i]) > config.sigmaThreshold * sigma + gradientAllowance + 1e-5) reject = true
                }
            }
            if (reject) { rejectedPixels++; continue }
            val next = weight + 1
            for (ch in 0 until channels) {
                val i = p * channels + ch; val delta = sampled[ch] - mean[i]
                mean[i] += delta / next; m2[i] += delta * (sampled[ch] - mean[i])
            }
            weights!!.increment(p)
        }
        stats = stats.copy(accepted = stats.accepted + 1, exposureSeconds = stats.exposureSeconds + exposureSeconds,
            registrationRmsPx = registration.rmsPixels, rotationDegrees = Math.toDegrees(atan2(s,c)),
            rejectedPixelSamples = stats.rejectedPixelSamples + rejectedPixels, elapsedMs = (System.nanoTime() - start) / 1_000_000,
            inputEncoding = image.encoding,
            maximumInputQuantizationError = max(stats.maximumInputQuantizationError, image.maximumQuantizationError))
        return DeepSkyFrameResult(true, null, stats)
    }

    fun snapshot(): DeepSkySnapshot? {
        if (stats.accepted == 0) return null
        val nativeAccumulator = native
        if (nativeAccumulator != null) {
            val finished = nativeAccumulator.finish()
            return DeepSkySnapshot(LinearImage(width, height, channels, finished.pixels),
                finished.coverage, stats)
        }
        return DeepSkySnapshot(LinearImage(width, height, channels, mean.copyOf()),
            DensePixelWeights(FloatArray(width * height) { weights!![it].toFloat() }), stats)
    }

    internal fun previewSnapshot(maxSide: Int): DeepSkySnapshot? {
        if (stats.accepted == 0) return null
        val grid = StackPreviewGrid(width, height, maxSide)
        val pixels = FloatArray(grid.width * grid.height * channels)
        val count = IntArray(grid.width * grid.height)
        val nativeAccumulator = native
        if (nativeAccumulator != null) {
            val rowMean = FloatArray(width * channels)
            val rowCounts = ShortArray(width)
            for (gy in 0 until grid.height) {
                val sourcePixel = grid.sourcePixel(gy * grid.width)
                val sourceY = sourcePixel / width
                nativeAccumulator.readRows(sourceY, 1, rowMean, null, rowCounts)
                for (gx in 0 until grid.width) {
                    val sourceX = grid.sourcePixel(gy * grid.width + gx) % width
                    val target = gy * grid.width + gx
                    count[target] = rowCounts[sourceX].toInt() and 0xffff
                    for (ch in 0 until channels) pixels[target * channels + ch] =
                        rowMean[sourceX * channels + ch]
                }
            }
        } else {
            for (target in count.indices) {
                val source = grid.sourcePixel(target)
                count[target] = weights!![source]
                for (ch in 0 until channels) pixels[target * channels + ch] = mean[source * channels + ch]
            }
        }
        return DeepSkySnapshot(LinearImage(grid.width, grid.height, channels, pixels),
            PixelCoverage.fromInts(count), stats)
    }

    /** Only the serialized science worker may read this view while accumulation is held. */
    internal fun borrowedSnapshot(): DeepSkySnapshot? {
        if (stats.accepted == 0) return null
        val nativeAccumulator = native
        if (nativeAccumulator != null) {
            val finished = nativeAccumulator.finish()
            return DeepSkySnapshot(LinearImage(width, height, channels, finished.pixels),
                finished.coverage, stats)
        }
        return DeepSkySnapshot(LinearImage(width, height, channels, mean),
            DensePixelWeights(FloatArray(width * height) { weights!![it].toFloat() }), stats)
    }

    /** Finish a sequential offline pass by moving its accumulated arrays into the result.
     * Reset/reuse can never mutate that result. Live callers must keep using snapshot(). */
    internal fun takeResult(): DeepSkySnapshot? {
        check(!livePreviewOverlap) { "Live accumulation requires an independent snapshot" }
        val result = if (stats.accepted == 0) null else {
            val nativeAccumulator = native
            if (nativeAccumulator != null) {
                val finished = nativeAccumulator.finish(releaseVariance = true)
                DeepSkySnapshot(LinearImage(width, height, channels, finished.pixels),
                    finished.coverage, stats)
            } else {
                DeepSkySnapshot(LinearImage(width, height, channels, mean),
                    DensePixelWeights(FloatArray(width * height) { weights!![it].toFloat() }), stats)
            }
        }
        reset()
        return result
    }

    companion object {
        const val MAX_STACK_FRAMES = 65_535
        const val FRAME_LIMITATION = "At most 65,535 frames can be accepted in one stack."
        // A slow renderer may retain snapshot A while the latest result is B and the
        // science worker allocates C. Budget all three transient image/weight pairs,
        // the bounded raw queue and decoder, accumulators, automatic background output,
        // and simultaneous sharpening/local-contrast scratch. Calibration is added by
        // the coordinator because its masters and sensor geometry can differ after binning.
        fun estimateWorkingBytes(width: Int, height: Int, channels: Int, previewMaxSide: Int? = null,
                                 inputStorageBytes: Long = width.toLong() * height * channels * 4,
                                 nativeAccumulator: Boolean = true): Long {
            val pixels = width.toLong() * height
            val floatingInput = pixels * channels * 4
            val javaAccumulator = if (nativeAccumulator) 0L else pixels * (channels * 8L + 2L)
            val base = if (previewMaxSide == null) width.toLong() * height * (channels * 24L + 30L) + 8L * 1024 * 1024
                else width.toLong() * height * (channels * 8L + 2L) +
                    StackPreviewGrid.workingBytes(width, height, channels, previewMaxSide) + 8L * 1024 * 1024
            return Math.addExact(base - floatingInput + inputStorageBytes, javaAccumulator)
        }
        // Decoder/master storage is supplied separately by the offline coordinator. Generic
        // sources keep old/new read headroom; only the explicit buffered contract saves it.
        fun estimateOfflineWorkingBytes(width: Int, height: Int, channels: Int, reusableInput: Boolean = false): Long =
            OfflineDeepSkyMemory.estimate(width, height, channels, reusableInput).peakBytes
        const val REJECTION_LIMITATION = "Temporal sigma rejection starts after 5 valid samples per pixel. Early outliers and persistent hot pixels are not guaranteed to be removed; use dark calibration/dithering for persistent defects. Uncalibrated by default."
    }
}
