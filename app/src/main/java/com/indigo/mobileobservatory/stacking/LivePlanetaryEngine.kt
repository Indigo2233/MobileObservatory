package com.indigo.mobileobservatory.stacking

import java.util.concurrent.CancellationException
import kotlin.math.ceil

/**
 * Single-worker, one-pass planetary accumulation. Scores in a bounded recent window gate
 * incoming frames; accepted contributions are never removed or reranked against future frames.
 * A bounded startup chooses a sharp reference before the first contribution; it then stays fixed.
 */
class LivePlanetaryEngine(
    private val memoryBudgetBytes: Long,
    private val options: PlanetaryOptions = PlanetaryOptions(),
    private val qualityKeepPercent: Double = 50.0,
    private val qualityWindowFrames: Int = 64,
    private val referenceWarmupFrames: Int = 3,
    private val previewMaxSide: Int? = null
) : LiveStackEngine {
    private var width = 0; private var height = 0; private var channels = 0
    private var sums = DoubleArray(0)
    private var weights = FloatArray(0)
    private var sourcePlane = FloatArray(0)
    private var reference: PlanetaryRegistration? = null
    private var bootstrapBest: LinearImage? = null
    private var bootstrapBestQuality = -1.0
    private var bootstrapBestFrame = 0L
    private var bootstrapValidFrames = 0
    private var improvedEdgeFailures = 0
    private var referenceRestartRequired = false
    private val accumulator = PlanetaryStacker()
    private val warpWorkspace = PlanetaryWarp.Workspace()
    private val hint = LocalShiftHint()
    private val qualityScores = DoubleArray(qualityWindowFrames.also { require(it in 2..4096) })
    private val sortedScores = DoubleArray(qualityWindowFrames)
    private var scoreCount = 0; private var nextScore = 0
    private var cancellationProbe: () -> Boolean = { false }
    private var received = 0L; private var qualityValid = 0L; private var accepted = 0L
    private var referenceFrame = 0L; private var referenceQuality = 0.0
    private var lastQuality = 0.0; private var threshold = 0.0
    private var totalCorrelation = 0.0; private var exposure = 0.0
    private var workingBytes = 0L
    private val modeCounts = linkedMapOf<PlanetaryRegistrationMode, Long>()
    private val rejections = linkedMapOf<String, Long>()

    init {
        require(memoryBudgetBytes > 0) { "memory_budget" }
        require(qualityKeepPercent.isFinite() && qualityKeepPercent > 0 && qualityKeepPercent <= 100)
        require(qualityWindowFrames in 2..4096)
        require(referenceWarmupFrames in 1..16)
        require(options.maxAlignmentPoints in 3..256 && options.maxGlobalShift in 2..128 && options.maxLocalShift in 2..16)
        require(options.alignmentPatchSize == null || options.alignmentPatchSize in 12..128)
        require(options.minimumGlobalCorrelation in .1..1.0 && options.minimumLocalCorrelation in .1..1.0)
        require(options.maximumEdgeResidual in .05..1.0)
    }

    private fun checkCancelled() {
        if (cancellationProbe()) throw CancellationException("Live planetary generation discarded")
    }

    override fun add(image: LinearImage, exposureSeconds: Double, cancelled: () -> Boolean): LiveFrameOutcome {
        cancellationProbe = cancelled
        try {
            checkCancelled()
            received++
            fun reject(reason: String, requiresReset: Boolean = false): LiveFrameOutcome {
                if (reason != PlanetaryRejection.EDGE_MISMATCH.name && reason != "REFERENCE_RESTART_REQUIRED") improvedEdgeFailures = 0
                rejections[reason] = (rejections[reason] ?: 0L) + 1
                return LiveFrameOutcome(false, reference?.points?.size ?: 0, 0.0, 0.0, reason, requiresReset)
            }
            if (referenceRestartRequired) return reject("REFERENCE_RESTART_REQUIRED", requiresReset = true)
            if (!exposureSeconds.isFinite() || exposureSeconds <= 0 || !(exposure + exposureSeconds).isFinite())
                return reject("INVALID_EXPOSURE")
            if (image.width < 12 || image.height < 12 || ((reference != null || bootstrapBest != null) &&
                (image.width != width || image.height != height || image.channels != channels))) return reject("GEOMETRY")
            if (image.pixelCount.toLong() > PlanetaryStacker.MAX_PIXELS) return reject("PIXEL_BUDGET")
            val bytes = estimateWorkingBytes(image.width, image.height, image.channels, previewMaxSide)
            if (bytes > memoryBudgetBytes) return reject("MEMORY_BUDGET")
            if (accepted >= PlanetaryStacker.MAX_SELECTED_FRAMES) return reject("FRAME_LIMIT")
            for (y in 0 until image.height) {
                checkCancelled()
                val start = y * image.width * image.channels
                for (i in start until start + image.width * image.channels)
                    if (!image.pixels[i].isFinite()) return reject("INVALID_PIXELS")
            }
            val roi = (options.roi ?: PixelRoi(0, 0, image.width, image.height)).checked(image.width, image.height)
            val quality = PlanetaryQualityEstimator.measure(image, roi, ::checkCancelled)
            lastQuality = quality.score
            if (quality.rejection != PlanetaryRejection.NONE) return reject(quality.rejection.name)
            qualityValid++
            qualityScores[nextScore] = quality.score
            nextScore = (nextScore + 1) % qualityWindowFrames
            scoreCount = minOf(qualityWindowFrames, scoreCount + 1)
            qualityScores.copyInto(sortedScores, endIndex = scoreCount)
            java.util.Arrays.sort(sortedScores, 0, scoreCount)
            val keep = ceil(scoreCount * qualityKeepPercent / 100).toInt().coerceIn(1, scoreCount)
            threshold = sortedScores[scoreCount - keep]

            var registration = reference
            var identityReferenceFrame = false
            if (registration == null) {
                bootstrapValidFrames++
                if (quality.score > bootstrapBestQuality) {
                    // Decode outputs can be borrowed workspace. Retain one private candidate
                    // and overwrite its pixels rather than holding camera/decoder ownership.
                    val previous = bootstrapBest
                    bootstrapBest = if (previous == null) image.deepCopy() else {
                        image.pixels.copyInto(previous.pixels)
                        previous
                    }
                    bootstrapBestQuality = quality.score
                    bootstrapBestFrame = received
                }
                width = image.width; height = image.height; channels = image.channels
                workingBytes = bytes
                if (bootstrapValidFrames < referenceWarmupFrames) return reject("REFERENCE_WARMUP")
                checkCancelled()
                val selected = checkNotNull(bootstrapBest)
                registration = PlanetaryRegistration(selected, roi, options, ::checkCancelled)
                sums = DoubleArray(image.pixels.size)
                weights = FloatArray(image.pixelCount)
                sourcePlane = FloatArray(image.pixelCount)
                reference = registration
                referenceFrame = bootstrapBestFrame
                referenceQuality = bootstrapBestQuality
                identityReferenceFrame = referenceFrame == received
                bootstrapBest = null
            }
            if (quality.score < threshold) return reject("QUALITY_THRESHOLD")
            val registered = checkNotNull(registration)
            val prepared = if (identityReferenceFrame) null else registered.prepareFrame(image, sourcePlane)
            val alignment = if (identityReferenceFrame) PlanetaryAlignment(PlanetaryShift(0.0, 0.0, 1.0), emptyList(), registered.points.size,
                if (!options.localAlignment) PlanetaryRegistrationMode.GLOBAL
                else if (registered.points.size < 3) PlanetaryRegistrationMode.GLOBAL_LOW_TEXTURE
                else PlanetaryRegistrationMode.MULTIPOINT)
            else registered.align(checkNotNull(prepared), hint)
            if (alignment.rejection != PlanetaryRejection.NONE) return reject(alignment.rejection.name)
            val warp = PlanetaryWarp(width, height, alignment, ::checkCancelled, warpWorkspace)
            if (warp.folded) return reject(PlanetaryRejection.FOLDED_WARP.name)
            if (prepared != null && registered.edgeResidual(prepared, warp) > options.maximumEdgeResidual) {
                // A poor startup reference can reject every later sharper view. Preserve the
                // existing coordinate system and counters, but surface an actionable restart
                // after repeated strong, consistently aligned improvements. Never relax the
                // edge gate or silently reanchor already accumulated pixels.
                improvedEdgeFailures = if (quality.score > referenceQuality * 1.15 && alignment.global.correlation >= .9)
                    improvedEdgeFailures + 1 else 0
                if (improvedEdgeFailures >= 3) {
                    referenceRestartRequired = true
                    return reject("REFERENCE_RESTART_REQUIRED", requiresReset = true)
                }
                return reject(PlanetaryRejection.EDGE_MISMATCH.name)
            }
            improvedEdgeFailures = 0
            accumulator.accumulate(image, sums, weights, warp, ::checkCancelled)
            // Only a full multipoint result is fed forward; a global or fallback frame means
            // the distortion field itself moved and the previous per-point guesses are stale.
            if (alignment.mode == PlanetaryRegistrationMode.MULTIPOINT)
                hint.record(registered.points, alignment)
            checkCancelled()
            accepted++
            exposure += exposureSeconds
            totalCorrelation += alignment.global.correlation
            modeCounts[alignment.mode] = (modeCounts[alignment.mode] ?: 0L) + 1
            // Alignment-point count is meaningful for planets; a pixel RMS/rotation was not fitted.
            return LiveFrameOutcome(true, registered.points.size, 0.0, 0.0)
        } catch (cancel: CancellationException) {
            discard()
            throw cancel
        } finally {
            cancellationProbe = { false }
        }
    }

    /** Cancellation may interrupt accumulation. Discard it rather than publishing partial counts. */
    private fun discard() {
        reference = null
        bootstrapBest = null; bootstrapBestQuality = -1.0; bootstrapBestFrame = 0
        bootstrapValidFrames = 0; improvedEdgeFailures = 0; referenceRestartRequired = false
        sums = DoubleArray(0); weights = FloatArray(0); sourcePlane = FloatArray(0)
        warpWorkspace.clear()
        hint.clear()
        width = 0; height = 0; channels = 0
        received = 0; qualityValid = 0; accepted = 0
        referenceFrame = 0; referenceQuality = 0.0; lastQuality = 0.0; threshold = 0.0
        exposure = 0.0; totalCorrelation = 0.0; workingBytes = 0
        scoreCount = 0; nextScore = 0
        modeCounts.clear(); rejections.clear()
    }

    override fun snapshot(state: LiveStackState): LiveStackSnapshot? {
        if (accepted == 0L) return null
        val output = FloatArray(sums.size)
        for (p in weights.indices) {
            val count = weights[p]
            if (count <= 0) continue
            for (channel in 0 until channels) output[p * channels + channel] = (sums[p * channels + channel] / count).toFloat()
        }
        return LiveStackSnapshot(LinearImage(width, height, channels, output), weights.copyOf(), state, snapshotDetails())
    }

    override fun previewSnapshot(state: LiveStackState, maxSide: Int): LiveStackSnapshot? {
        if (accepted == 0L) return null
        val grid = StackPreviewGrid(width, height, maxSide)
        val coverage = FloatArray(grid.width * grid.height) { weights[grid.sourcePixel(it)] }
        val output = FloatArray(coverage.size * channels) { i ->
            val p = i / channels
            if (coverage[p] > 0) (sums[grid.sourcePixel(p) * channels + i % channels] / coverage[p]).toFloat() else 0f
        }
        return LiveStackSnapshot(LinearImage(grid.width, grid.height, channels, output), coverage, state, snapshotDetails())
    }

    override fun exportSnapshot(state: LiveStackState): LiveStackSnapshot? {
        if (accepted == 0L) return null
        val output = FloatArray(sums.size) { i ->
            val count = weights[i / channels]
            if (count > 0) (sums[i] / count).toFloat() else 0f
        }
        return LiveStackSnapshot(LinearImage(width, height, channels, output), weights, state, snapshotDetails())
    }

    override fun snapshotDetails(): Map<String, Any> = mapOf(
            "algorithm" to "planetary_online_rolling_quality_ncc_double_accumulation",
            "target" to "planetary",
            "qualitySelection" to "recent_window_threshold_including_current_frame_with_ties",
            "qualityKeepPercent" to qualityKeepPercent,
            "qualityWindowFrames" to qualityWindowFrames,
            "qualityWindowSamples" to scoreCount,
            "qualityThreshold" to threshold,
            "lastQualityScore" to lastQuality,
            "qualityValidFrames" to qualityValid,
            "engineReceivedFrames" to received,
            "engineAcceptedFrames" to accepted,
            "referencePolicy" to "best_of_bounded_quality_valid_startup_then_fixed",
            "referenceWarmupFrames" to referenceWarmupFrames,
            "referenceWarmupValidFrames" to bootstrapValidFrames,
            "referenceWarmupDiscardedFrames" to (bootstrapValidFrames - 1).coerceAtLeast(0),
            "referenceRestartRequired" to referenceRestartRequired,
            "referenceInputFrame" to referenceFrame,
            "referenceQuality" to referenceQuality,
            "planetaryOptions" to options.copy(memoryBudgetBytes = memoryBudgetBytes).toString(),
            "targetRoi" to (options.roi?.toString() ?: "full_frame_${width}x$height"),
            "alignmentPoints" to (reference?.points?.size ?: 0),
            "registrationModeCounts" to modeCounts.mapKeys { it.key.name }.toMap(),
            "rejectionCounts" to rejections.toMap(),
            "meanCorrelation" to if (accepted > 0) totalCorrelation / accepted else 0.0,
            "effectiveExposureSeconds" to exposure,
            "estimatedWorkingBytes" to workingBytes,
            "registrationRmsAvailable" to false,
            "rotationEstimated" to false,
            "derotation" to "none",
            "onlineLimitation" to ONLINE_LIMITATION
        )

    companion object {
        const val ONLINE_LIMITATION = "One-pass selection uses a recent quality-score window; ties may exceed the requested fraction. A bounded startup chooses the sharpest initial valid reference before accumulating; earlier warmup frames are not contributions. The reference then stays fixed and past contributions stay in the stack. A persistently incompatible reference requires an explicit restart. This is not global best-percent selection. No planet derotation or sharpening is applied."

        /** Persistent double sums/coverage + reference/reusable source planes; raw queue/input,
         * three retained snapshot pairs, decode/render scratch, largest two displacement meshes. */
        fun estimateWorkingBytes(width: Int, height: Int, channels: Int, previewMaxSide: Int? = null): Long {
            require(width > 0 && height > 0 && channels in listOf(1, 3))
            val pixels = Math.multiplyExact(width.toLong(), height.toLong())
            val images = Math.multiplyExact(pixels, if (previewMaxSide == null) channels * 40L + 36L else channels * 12L + 12L)
            val preview = previewMaxSide?.let { StackPreviewGrid.workingBytes(width, height, channels, it) } ?: 0L
            return Math.addExact(Math.addExact(images, meshBytes(width, height)), 8L * 1024 * 1024 + preview)
        }

        internal fun meshBytes(width: Int, height: Int): Long {
            val meshColumns = (width.toLong() + 3) / 4 + 1
            val meshRows = (height.toLong() + 3) / 4 + 1
            return Math.multiplyExact(Math.multiplyExact(meshColumns, meshRows), 8L)
        }
    }
}
