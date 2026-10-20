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
    val physicalEdges: PlanetaryImageEdges get() = PlanetaryImageEdges()
    /** The caller separately budgets bounded native decoder/calibration scratch. */
    val boundedDecode: Boolean get() = false
    fun readFrame(index: Long): LinearImage
    /** Zero means unknown, never inferred from video FPS. */
    fun exposureSeconds(index: Long): Double = 0.0
}

/** Synchronous bounded decoder. Destinations belong to the caller and must never be retained.
 * Luminance must equal [PlanetarySampling.luma] after the same calibration/demosaic/Bin steps.
 * The independently owned [readFrame] contract remains available for other consumers. */
internal interface BufferedPlanetaryFrameSource : PlanetaryFrameSource {
    override val boundedDecode: Boolean get() = true
    fun readFrameInto(index: Long, destination: FloatArray): LinearImage
    fun readLuminanceInto(index: Long, destination: FloatArray): LinearImage
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
        const val MAX_PIXELS = LinearImage.MAX_PIXELS
        const val MAX_SELECTED_FRAMES = 10_000_000L // Exact integer per-pixel counts in Float.
        /** Includes decode scratch, two input images, two gray planes, double sums, weights, output + preview/post scratch. */
        fun estimatedPeakBytes(width: Int, height: Int, channels: Int, boundedDecode: Boolean = false,
                               reusableInput: Boolean = false): Long =
            if (reusableInput) {
                require(boundedDecode)
                // One caller-owned Float input becomes the result. Double sums, coverage and
                // two gray planes stay resident. Reserve the largest reusable dx/dy mesh plus
                // 8 MiB for quality/index/metadata; decoder/calibration tiles are added separately.
                val meshColumns = (width.toLong() + 3) / 4 + 1
                val meshRows = (height.toLong() + 3) / 4 + 1
                width.toLong() * height * (channels * 12L + 12L) + meshColumns * meshRows * 8 + 8L * 1024 * 1024
            } else if (boundedDecode) {
                // Reference + old/new input + result (4 Float channel images), Double sums,
                // reference/current reusable gray planes and Float coverage. Post-processing
                // runs after this worker returns; its lower live set fits the same reserve.
                width.toLong() * height * (channels * 24L + 12L) + 8L * 1024 * 1024
            } else Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), channels * 32L + 24L) + 2L * 1024 * 1024
    }

    fun analyze(source: PlanetaryFrameSource, options: PlanetaryOptions = PlanetaryOptions(), cacheDirectory: File,
                control: PlanetaryControl = PlanetaryControl()): PlanetaryAnalysis {
        val start = System.nanoTime()
        validate(source, options)
        control.checkCancelled()
        val buffered = source as? BufferedPlanetaryFrameSource
        val luminance = buffered?.let { FloatArray(source.width * source.height) }
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
                    val image = if (buffered == null) source.readFrame(frame)
                        else buffered.readLuminanceInto(frame, checkNotNull(luminance)).also {
                            require(it.pixels === luminance) { "Decoder did not fill the caller's buffer" }
                        }
                    validateFrame(image, source, if (buffered == null) source.channels else 1)
                    val q = PlanetaryQualityEstimator.measure(image, roi, source.physicalEdges, control.checkCancelled)
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
                source.width, source.height, source.channels,
                estimatedPeakBytes(source.width, source.height, source.channels, source.boundedDecode, buffered != null),
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
        val buffered = source as? BufferedPlanetaryFrameSource
        // Generic sources keep their independently owned reference. Bounded decoders only
        // retain reference luminance and reread selected RGB into a single caller-owned input.
        val reference = if (buffered == null) source.readFrame(analysis.referenceFrame).also { validateFrame(it, source) } else null
        val options = analysis.options
        val roi = options.roi ?: PixelRoi(0, 0, source.width, source.height)
        val registration = if (buffered == null) PlanetaryRegistration(checkNotNull(reference), roi, options, control.checkCancelled)
            else {
                val plane = FloatArray(source.width * source.height)
                val gray = buffered.readLuminanceInto(analysis.referenceFrame, plane)
                validateFrame(gray, source, 1)
                require(gray.pixels === plane) { "Decoder did not fill the caller's buffer" }
                PlanetaryRegistration.fromLuminance(gray, source.channels, roi, options, control.checkCancelled)
            }
        val framePixels = buffered?.let { FloatArray(source.width * source.height * source.channels) }
        val sums = DoubleArray(source.width * source.height * source.channels)
        val weights = FloatArray(source.width * source.height)
        var grayScratch: FloatArray? = null
        val warpWorkspace = PlanetaryWarp.Workspace()
        val hint = if (options.localAlignment) LocalShiftHint() else null
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
                    val image = if (buffered != null) buffered.readFrameInto(frame, checkNotNull(framePixels)).also {
                        require(it.pixels === framePixels) { "Decoder did not fill the caller's buffer" }
                    } else if (frame == analysis.referenceFrame) checkNotNull(reference) else source.readFrame(frame)
                    validateFrame(image, source)
                    control.progress(PlanetaryProgress(PlanetaryPhase.REGISTER, processed, selected))
                    val prepared = if (frame == analysis.referenceFrame) null else registration.prepareFrame(image,
                        grayScratch ?: FloatArray(source.width * source.height).also { grayScratch = it })
                    val alignment = if (frame == analysis.referenceFrame) PlanetaryAlignment(
                        PlanetaryShift(0.0, 0.0, 1.0), emptyList(), registration.points.size,
                        if (!options.localAlignment) PlanetaryRegistrationMode.GLOBAL else if (registration.points.size < 3) PlanetaryRegistrationMode.GLOBAL_LOW_TEXTURE else PlanetaryRegistrationMode.MULTIPOINT
                    ) else registration.align(checkNotNull(prepared), hint)
                    var reason = alignment.rejection
                    if (reason == PlanetaryRejection.NONE) {
                        val warp = PlanetaryWarp(source.width, source.height, alignment, control.checkCancelled, warpWorkspace)
                        if (warp.folded) reason = PlanetaryRejection.FOLDED_WARP
                        else if (prepared != null && registration.edgeResidual(prepared, warp) > options.maximumEdgeResidual) reason = PlanetaryRejection.EDGE_MISMATCH
                        else {
                            control.progress(PlanetaryProgress(PlanetaryPhase.STACK, processed, selected))
                            accumulate(image, sums, weights, warp, control.checkCancelled)
                            // Only a full multipoint result is fed forward: a global or
                            // fallback frame signals that the field itself has moved.
                            if (hint != null && alignment.mode == PlanetaryRegistrationMode.MULTIPOINT)
                                hint.record(registration.points, alignment)
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
        // All inputs have been consumed. Transfer this buffer to the immutable result after
        // normalization; the source never owns it and a later stack allocates its own input.
        val output = framePixels ?: FloatArray(sums.size)
        for (y in 0 until source.height) {
            control.checkCancelled()
            for (x in 0 until source.width) {
                val p = y * source.width + x
                for (channel in 0 until source.channels) output[p * source.channels + channel] =
                    if (weights[p] > 0) (sums[p * source.channels + channel] / weights[p]).toFloat() else 0f
            }
        }
        control.progress(PlanetaryProgress(PlanetaryPhase.COMPLETE, selected, selected))
        return PlanetaryStackResult(LinearImage(source.width, source.height, source.channels, output), weights,
            PlanetaryStackStats(source.frameCount, analysis.validFrames, selected, accepted, analysis.referenceFrame, registration.points.size,
                modes, reasons, totalCorrelation / accepted, exposure, unknownExposure, (System.nanoTime() - start) / 1_000_000, analysis.estimatedPeakBytes))
    }

    private fun validate(source: PlanetaryFrameSource, options: PlanetaryOptions) {
        require(source.width >= 12 && source.height >= 12 && source.channels in listOf(1, 3) && source.frameCount > 0) { "Unsupported or empty recording" }
        require(source.width.toLong() * source.height <= MAX_PIXELS) { "processing_size" }
        val bytes = estimatedPeakBytes(source.width, source.height, source.channels, source.boundedDecode,
            source is BufferedPlanetaryFrameSource)
        require(bytes <= options.memoryBudgetBytes) { "Stack needs about ${bytes / 1048576} MiB; choose a smaller recording ROI or free memory" }
        options.roi?.checked(source.width, source.height)
        require(options.maxAlignmentPoints in 3..256 && options.maxGlobalShift in 2..128 && options.maxLocalShift in 2..16)
        require(options.alignmentPatchSize == null || options.alignmentPatchSize in 12..128)
        require(options.minimumGlobalCorrelation in .1..1.0 && options.minimumLocalCorrelation in .1..1.0)
        require(options.maximumEdgeResidual in .05..1.0)
    }

    private fun validateFrame(image: LinearImage, source: PlanetaryFrameSource, channels: Int = source.channels) {
        require(image.width == source.width && image.height == source.height && image.channels == channels &&
            image.pixels.size.toLong() == source.width.toLong() * source.height * channels) { "Recording frame dimensions changed or data are truncated" }
    }

    internal fun accumulate(image: LinearImage, sums: DoubleArray, weights: FloatArray, warp: PlanetaryWarp, checkCancelled: () -> Unit) {
        val width = image.width; val height = image.height; val channels = image.channels
        val pixels = image.pixels
        val table = warp.offsets
        val dxMesh = warp.dx; val dyMesh = warp.dy
        for (y in 0 until height) {
            checkCancelled()
            val rowTop = table.rowBase(y); val rowBottom = table.rowNextBase(y)
            val meshY = table.rowFraction(y); val notMeshY = 1 - meshY
            for (x in 0 until width) {
                val column = table.columnIndex(x); val nextColumn = table.columnNext(x)
                val meshX = table.columnFraction(x); val notMeshX = 1 - meshX
                val dxTop = dxMesh[rowTop + column] * notMeshX + dxMesh[rowTop + nextColumn] * meshX
                val dxBottom = dxMesh[rowBottom + column] * notMeshX + dxMesh[rowBottom + nextColumn] * meshX
                val dyTop = dyMesh[rowTop + column] * notMeshX + dyMesh[rowTop + nextColumn] * meshX
                val dyBottom = dyMesh[rowBottom + column] * notMeshX + dyMesh[rowBottom + nextColumn] * meshX
                val sx = x + (dxTop * notMeshY + dxBottom * meshY)
                val sy = y + (dyTop * notMeshY + dyBottom * meshY)
                if (sx < 0 || sy < 0 || sx > width - 1 || sy > height - 1) continue
                val ix = sx.toInt(); val iy = sy.toInt(); val nx = min(width - 1, ix + 1); val ny = min(height - 1, iy + 1)
                val fx = sx - ix; val fy = sy - iy
                val a = (iy * width + ix) * channels; val b = (iy * width + nx) * channels
                val c = (ny * width + ix) * channels; val d = (ny * width + nx) * channels
                val p = y * width + x
                val oneMinusFx = 1 - fx; val oneMinusFy = 1 - fy
                // A non-finite tap always propagates into its weighted sum, so testing the
                // interpolated sample is equivalent to testing four inputs per channel and it
                // keeps every RGB channel on the same coverage decision.
                if (channels == 1) {
                    val value = (pixels[a] * oneMinusFx + pixels[b] * fx) * oneMinusFy +
                        (pixels[c] * oneMinusFx + pixels[d] * fx) * fy
                    if (!value.isFinite()) continue
                    sums[p] += value
                    weights[p] += 1f
                } else {
                    val first = (pixels[a] * oneMinusFx + pixels[b] * fx) * oneMinusFy +
                        (pixels[c] * oneMinusFx + pixels[d] * fx) * fy
                    if (!first.isFinite()) continue
                    val second = (pixels[a + 1] * oneMinusFx + pixels[b + 1] * fx) * oneMinusFy +
                        (pixels[c + 1] * oneMinusFx + pixels[d + 1] * fx) * fy
                    if (!second.isFinite()) continue
                    val third = (pixels[a + 2] * oneMinusFx + pixels[b + 2] * fx) * oneMinusFy +
                        (pixels[c + 2] * oneMinusFx + pixels[d + 2] * fx) * fy
                    if (!third.isFinite()) continue
                    sums[p * 3] += first; sums[p * 3 + 1] += second; sums[p * 3 + 2] += third
                    weights[p] += 1f
                }
            }
        }
    }
}
