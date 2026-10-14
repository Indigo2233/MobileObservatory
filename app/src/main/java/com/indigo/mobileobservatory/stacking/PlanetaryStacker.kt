package com.indigo.mobileobservatory.stacking

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.EOFException
import kotlin.math.*

/** Caller owns and closes the source. Each read must return an independently owned linear image. */
interface PlanetaryFrameSource : Closeable {
    val frameCount: Long
    val width: Int
    val height: Int
    val channels: Int
    fun readFrame(index: Long): LinearImage
    /** Zero means unknown, never inferred from video FPS. */
    fun exposureSeconds(index: Long): Double = 0.0
}

data class PlanetaryOptions(
    val roi: PixelRoi? = null,
    val localAlignment: Boolean = true,
    val alignmentPatchSize: Int? = null,
    val maxAlignmentPoints: Int = 96,
    val maxGlobalShift: Int = 32,
    val maxLocalShift: Int = 5,
    val minimumGlobalCorrelation: Double = .7,
    val minimumLocalCorrelation: Double = .65,
    val maximumEdgeResidual: Double = .24,
    val memoryBudgetBytes: Long = 256L * 1024 * 1024
)

data class PlanetarySelection(val percent: Double = 25.0, val count: Long? = null) {
    internal fun selectedCount(valid: Long): Long {
        require(percent.isFinite() && percent > 0 && percent <= 100) { "Keep percentage must be in (0, 100]" }
        require(count == null || count > 0) { "Keep frame count must be positive" }
        return (count ?: ceil(valid * percent / 100).toLong()).coerceIn(1, valid)
    }
}

enum class PlanetaryPhase { READ, ANALYZE, REGISTER, STACK, COMPLETE }
data class PlanetaryProgress(val phase: PlanetaryPhase, val completed: Long, val total: Long)
data class PlanetaryControl(
    val checkCancelled: () -> Unit = {},
    val progress: (PlanetaryProgress) -> Unit = {}
)

/** Fixed-size score histogram + a disk-spooled 5-byte record per input frame; no frame-count-sized array. */
class PlanetaryAnalysis internal constructor(
    internal val indexFile: File,
    val frameCount: Long,
    val validFrames: Long,
    val referenceFrame: Long,
    val histogram: LongArray,
    val rejectionCounts: Map<PlanetaryRejection, Long>,
    val options: PlanetaryOptions,
    val width: Int,
    val height: Int,
    val channels: Int,
    val estimatedPeakBytes: Long,
    val elapsedMillis: Long,
    internal val rankHistogram: LongArray
) : Closeable {
    @Volatile private var closed = false
    internal fun checkOpen() { check(!closed && indexFile.isFile) { "Quality analysis has been closed; analyze the source again" } }
    override fun close() { closed = true; indexFile.delete() }
}

data class PlanetaryStackStats(
    val inputFrames: Long,
    val qualityValidFrames: Long,
    val selectedFrames: Long,
    val stackedFrames: Long,
    val referenceFrame: Long,
    val alignmentPoints: Int,
    val modeCounts: Map<PlanetaryRegistrationMode, Long>,
    val rejectionCounts: Map<PlanetaryRejection, Long>,
    val meanCorrelation: Double,
    val effectiveExposureSeconds: Double,
    val framesWithUnknownExposure: Long,
    val elapsedMillis: Long,
    val estimatedPeakBytes: Long,
    val cpuWorkers: Int = 1
)
data class PlanetaryStackResult(val image: LinearImage, val weights: FloatArray, val stats: PlanetaryStackStats)

/**
 * Sequential, bounded-memory, cancellable two-pass lucky-imaging stacker. Run off the main thread.
 * Selection is a 16-bit quality order statistic, resolved deterministically by frame order for ties.
 * Up to 65,536 histogram counters stay in RAM regardless of recording duration.
 * Reference is the best individual frame. APs refine local motion; weights blend displacement fields.
 * Output stays linear and unsharpened. Deconvolution and planet derotation are intentionally separate.
 */
class PlanetaryStacker {
    companion object {
        const val MAX_PIXELS = 4_194_304L
        const val MAX_SELECTED_FRAMES = 10_000_000L // Exact integer per-pixel counts in Float.
        /** Includes decode scratch, two input images, two gray planes, double sums, weights, output + preview/post scratch. */
        fun estimatedPeakBytes(width: Int, height: Int, channels: Int): Long =
            Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), channels * 32L + 24L) + 2L * 1024 * 1024
    }

    fun analyze(source: PlanetaryFrameSource, options: PlanetaryOptions = PlanetaryOptions(), cacheDirectory: File,
                control: PlanetaryControl = PlanetaryControl()): PlanetaryAnalysis {
        val start = System.nanoTime()
        validate(source, options)
        control.checkCancelled()
        require(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "Cannot create quality cache; choose writable storage" }
        val needed = Math.addExact(Math.multiplyExact(source.frameCount, 5L), 1024L * 1024)
        require(cacheDirectory.usableSpace >= needed) { "Not enough storage for quality analysis; free at least $needed bytes" }
        val index = File.createTempFile("planetary-quality-", ".idx", cacheDirectory)
        try {
            val ranks = LongArray(65536)
            val distribution = LongArray(64)
            val reasons = linkedMapOf<PlanetaryRejection, Long>()
            var bestScore = -1.0; var bestFrame = -1L; var valid = 0L
            val roi = options.roi ?: PixelRoi(0, 0, source.width, source.height)
            DataOutputStream(index.outputStream().buffered(64 * 1024)).use { out ->
                var frame = 0L
                while (frame < source.frameCount) {
                    control.checkCancelled()
                    control.progress(PlanetaryProgress(PlanetaryPhase.READ, frame, source.frameCount))
                    val image = source.readFrame(frame)
                    validateFrame(image, source)
                    val q = PlanetaryQualityEstimator.measure(image, roi, control.checkCancelled)
                    val rank = (q.score * 65535).roundToInt().coerceIn(0, 65535)
                    out.writeInt(rank); out.writeByte(q.rejection.ordinal)
                    if (q.rejection == PlanetaryRejection.NONE) {
                        valid++; ranks[rank]++; distribution[rank / 1024]++
                        if (q.score > bestScore) { bestScore = q.score; bestFrame = frame }
                    } else reasons[q.rejection] = (reasons[q.rejection] ?: 0L) + 1
                    frame++
                    control.progress(PlanetaryProgress(PlanetaryPhase.ANALYZE, frame, source.frameCount))
                }
            }
            require(valid > 0) { "No usable target frames; adjust ROI, exposure or focus (${reasons.entries.joinToString { "${it.key}: ${it.value}" }})" }
            return PlanetaryAnalysis(index, source.frameCount, valid, bestFrame, distribution, reasons, options,
                source.width, source.height, source.channels, estimatedPeakBytes(source.width, source.height, source.channels),
                (System.nanoTime() - start) / 1_000_000, ranks)
        } catch (error: Throwable) { index.delete(); throw error }
    }

    fun stack(source: PlanetaryFrameSource, analysis: PlanetaryAnalysis,
              selection: PlanetarySelection = PlanetarySelection(), control: PlanetaryControl = PlanetaryControl()): PlanetaryStackResult {
        val start = System.nanoTime()
        analysis.checkOpen()
        require(source.frameCount == analysis.frameCount && source.width == analysis.width && source.height == analysis.height && source.channels == analysis.channels) {
            "Source changed since analysis; analyze it again"
        }
        validate(source, analysis.options)
        val selected = selection.selectedCount(analysis.validFrames)
        require(selected <= MAX_SELECTED_FRAMES) { "Keep at most $MAX_SELECTED_FRAMES frames in a single stack" }
        var above = 0L; var threshold = 65535
        while (threshold > 0 && above + analysis.rankHistogram[threshold] < selected) { above += analysis.rankHistogram[threshold]; threshold-- }
        var tiesRemaining = selected - above
        control.checkCancelled()
        val reference = source.readFrame(analysis.referenceFrame)
        validateFrame(reference, source)
        val options = analysis.options
        val registration = PlanetaryRegistration(reference, options.roi ?: PixelRoi(0, 0, source.width, source.height), options, control.checkCancelled)
        val sums = DoubleArray(reference.pixels.size)
        val weights = FloatArray(source.width * source.height)
        val modes = linkedMapOf<PlanetaryRegistrationMode, Long>()
        val reasons = analysis.rejectionCounts.toMutableMap()
        var accepted = 0L; var processed = 0L; var totalCorrelation = 0.0; var exposure = 0.0; var unknownExposure = 0L
        DataInputStream(analysis.indexFile.inputStream().buffered(64 * 1024)).use { input ->
            var frame = 0L
            while (frame < source.frameCount) {
                control.checkCancelled()
                val rank: Int; val rejected: Int
                try { rank = input.readInt(); rejected = input.readUnsignedByte() } catch (e: EOFException) {
                    throw IllegalStateException("Quality cache is truncated; analyze again", e)
                }
                val choose = rejected == PlanetaryRejection.NONE.ordinal && (rank > threshold || (rank == threshold && tiesRemaining > 0))
                if (choose) {
                    if (rank == threshold) tiesRemaining--
                    control.progress(PlanetaryProgress(PlanetaryPhase.READ, processed, selected))
                    val image = if (frame == analysis.referenceFrame) reference else source.readFrame(frame)
                    validateFrame(image, source)
                    control.progress(PlanetaryProgress(PlanetaryPhase.REGISTER, processed, selected))
                    val prepared = if (frame == analysis.referenceFrame) null else registration.prepareFrame(image)
                    val alignment = if (frame == analysis.referenceFrame) PlanetaryAlignment(
                        PlanetaryShift(0.0, 0.0, 1.0), emptyList(), registration.points.size,
                        if (!options.localAlignment) PlanetaryRegistrationMode.GLOBAL else if (registration.points.size < 3) PlanetaryRegistrationMode.GLOBAL_LOW_TEXTURE else PlanetaryRegistrationMode.MULTIPOINT
                    ) else registration.align(checkNotNull(prepared))
                    var reason = alignment.rejection
                    if (reason == PlanetaryRejection.NONE) {
                        val warp = PlanetaryWarp(source.width, source.height, alignment, control.checkCancelled)
                        if (warp.folded) reason = PlanetaryRejection.FOLDED_WARP
                        else if (prepared != null && registration.edgeResidual(prepared, warp) > options.maximumEdgeResidual) reason = PlanetaryRejection.EDGE_MISMATCH
                        else {
                            control.progress(PlanetaryProgress(PlanetaryPhase.STACK, processed, selected))
                            accumulate(image, sums, weights, warp, control.checkCancelled)
                            accepted++; totalCorrelation += alignment.global.correlation
                            modes[alignment.mode] = (modes[alignment.mode] ?: 0L) + 1
                            val seconds = source.exposureSeconds(frame)
                            if (seconds.isFinite() && seconds > 0) exposure += seconds else unknownExposure++
                        }
                    }
                    if (reason != PlanetaryRejection.NONE) reasons[reason] = (reasons[reason] ?: 0L) + 1
                    processed++
                }
                frame++
            }
        }
        check(processed == selected) { "Quality cache changed; analyze again" }
        require(accepted > 0) { "No frames passed registration; use a larger ROI or improve tracking" }
        val output = FloatArray(sums.size)
        for (y in 0 until source.height) {
            control.checkCancelled()
            for (x in 0 until source.width) {
                val p = y * source.width + x
                if (weights[p] > 0) for (channel in 0 until source.channels) output[p * source.channels + channel] =
                    (sums[p * source.channels + channel] / weights[p]).toFloat()
            }
        }
        control.progress(PlanetaryProgress(PlanetaryPhase.COMPLETE, selected, selected))
        return PlanetaryStackResult(LinearImage(source.width, source.height, source.channels, output), weights,
            PlanetaryStackStats(source.frameCount, analysis.validFrames, selected, accepted, analysis.referenceFrame, registration.points.size,
                modes, reasons, totalCorrelation / accepted, exposure, unknownExposure, (System.nanoTime() - start) / 1_000_000, analysis.estimatedPeakBytes))
    }

    private fun validate(source: PlanetaryFrameSource, options: PlanetaryOptions) {
        require(source.width >= 12 && source.height >= 12 && source.channels in listOf(1, 3) && source.frameCount > 0) { "Unsupported or empty recording" }
        require(source.width.toLong() * source.height <= MAX_PIXELS) { "Recording exceeds 4 megapixels; record a smaller target ROI" }
        val bytes = estimatedPeakBytes(source.width, source.height, source.channels)
        require(bytes <= options.memoryBudgetBytes) { "Stack needs about ${bytes / 1048576} MiB; choose a smaller recording ROI or free memory" }
        options.roi?.checked(source.width, source.height)
        require(options.maxAlignmentPoints in 3..256 && options.maxGlobalShift in 2..128 && options.maxLocalShift in 2..16)
        require(options.alignmentPatchSize == null || options.alignmentPatchSize in 12..128)
        require(options.minimumGlobalCorrelation in .1..1.0 && options.minimumLocalCorrelation in .1..1.0)
        require(options.maximumEdgeResidual in .05..1.0)
    }

    private fun validateFrame(image: LinearImage, source: PlanetaryFrameSource) {
        require(image.width == source.width && image.height == source.height && image.channels == source.channels &&
            image.pixels.size.toLong() == source.width.toLong() * source.height * source.channels) { "Recording frame dimensions changed or data are truncated" }
    }

    internal fun accumulate(image: LinearImage, sums: DoubleArray, weights: FloatArray, warp: PlanetaryWarp, checkCancelled: () -> Unit) {
        val width = image.width; val height = image.height; val channels = image.channels
        for (y in 0 until height) {
            checkCancelled()
            for (x in 0 until width) {
                val sx = x + warp.offset(warp.dx, x, y); val sy = y + warp.offset(warp.dy, x, y)
                if (sx < 0 || sy < 0 || sx > width - 1 || sy > height - 1) continue
                val ix = sx.toInt(); val iy = sy.toInt(); val nx = min(width - 1, ix + 1); val ny = min(height - 1, iy + 1)
                val fx = sx - ix; val fy = sy - iy
                val a = (iy * width + ix) * channels; val b = (iy * width + nx) * channels
                val c = (ny * width + ix) * channels; val d = (ny * width + nx) * channels
                // Skip the whole pixel on non-finite input so RGB channels always have identical coverage.
                var finite = true
                for (channel in 0 until channels) {
                    if (!image.pixels[a + channel].isFinite() || !image.pixels[b + channel].isFinite() ||
                        !image.pixels[c + channel].isFinite() || !image.pixels[d + channel].isFinite()) { finite = false; break }
                }
                if (!finite) continue
                val p = y * width + x
                for (channel in 0 until channels) {
                    val value = (image.pixels[a + channel] * (1 - fx) + image.pixels[b + channel] * fx) * (1 - fy) +
                        (image.pixels[c + channel] * (1 - fx) + image.pixels[d + channel] * fx) * fy
                    sums[p * channels + channel] += value
                }
                weights[p]++
            }
        }
    }
}
