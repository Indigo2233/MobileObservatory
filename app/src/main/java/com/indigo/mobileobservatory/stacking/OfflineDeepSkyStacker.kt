package com.indigo.mobileobservatory.stacking

data class OfflineDeepSkyStats(
    val stack: DeepSkyStats,
    val inputFrames: Long,
    val referenceFrame: Long,
    val unknownExposureFrames: Long,
    val rejectionCounts: Map<DeepSkyRejection, Long>,
    val elapsedMillis: Long,
    val referenceQuality: DeepSkyReferenceQuality? = null
)

data class OfflineDeepSkyResult(val image: LinearImage, val weights: PixelWeights, val stats: OfflineDeepSkyStats)

/**
 * Sequential star-field registration and online temporal rejection, using exactly the same
 * science engine as live deep-sky stacking. No lucky-imaging ranking, planetary AP warp or
 * frame-rate-derived exposure is used. The source retains ownership of its reader.
 */
class OfflineDeepSkyStacker {
    fun stack(
        source: PlanetaryFrameSource,
        config: DeepSkyConfig = DeepSkyConfig(),
        checkCancelled: () -> Unit = {},
        progress: (completed: Long, total: Long, stats: DeepSkyStats) -> Unit = { _, _, _ -> },
        decoderWorkingBytes: Long = 0,
        retainedCalibrationBytes: Long = 0
    ): OfflineDeepSkyResult {
        require(source.frameCount in 1..MAX_FRAMES) { "deep_sky_frame_count" }
        require(source.width > 0 && source.height > 0 && source.channels in listOf(1, 3)) { "source_geometry_changed" }
        require(source.width.toLong() * source.height <= LinearImage.MAX_PIXELS) { "memory_budget" }
        val buffered = source as? BufferedPlanetaryFrameSource
        val memory = OfflineDeepSkyMemory.estimate(source.width, source.height, source.channels, buffered != null,
            decoderWorkingBytes, retainedCalibrationBytes)
        require(memory.stackingBytes <= config.memoryBudgetBytes) { "memory_budget" }
        checkCancelled()
        val start = System.nanoTime()
        val referenceIndices = referenceIndices(source.frameCount)
        val algorithm = DeepSkyStacker(config, livePreviewOverlap = false, reusableOfflineInput = buffered != null)
        val referenceWorkspace = StarRegistration.Workspace()
        try {
            var input = if (buffered != null) FloatArray(source.width * source.height * source.channels) else null
            fun readFrame(index: Long): LinearImage {
                val image = if (buffered == null) source.readFrame(index) else
                    buffered.readFrameInto(index, checkNotNull(input)).also {
                        require(it.pixels === input) { "source_buffer_changed" }
                    }
                require(image.width == source.width && image.height == source.height && image.channels == source.channels) {
                    "source_geometry_changed"
                }
                return image
            }
            val referenceSamples = referenceIndices.mapIndexed { completed, index ->
                checkCancelled()
                val image = readFrame(index)
                val field = StarRegistration.detect(image, workspace = referenceWorkspace) {
                    checkCancelled()
                    false
                }
                progress(completed + 1L, referenceIndices.size + source.frameCount,
                    DeepSkyStats(received = completed + 1, estimatedWorkingBytes = memory.peakBytes))
                DeepSkyReferenceSample(index, field)
            }
            val reference = DeepSkyReferenceSelector.choose(referenceSamples, config)
                ?: error("deep_sky_no_accepted_frames")
            algorithm.primeReference(reference.field, source.width, source.height, source.channels)
            val rejected = linkedMapOf<DeepSkyRejection, Long>()
            var unknown = 0L
            var index = 0L
            while (index < source.frameCount) {
                checkCancelled()
                val image = readFrame(index)
                val exposure = source.exposureSeconds(index)
                val outcome = algorithm.add(image, exposure) { checkCancelled(); false }
                if (outcome.accepted) {
                    if (exposure == 0.0) unknown++
                } else outcome.reason?.let { rejected[it] = (rejected[it] ?: 0L) + 1L }
                index++
                progress(referenceIndices.size + index, referenceIndices.size + source.frameCount,
                    outcome.stats.copy(estimatedWorkingBytes = memory.peakBytes))
                if (!outcome.accepted && outcome.reason == DeepSkyRejection.FRAME_LIMIT)
                    error("deep_sky_frame_count")
            }
            checkCancelled()
            input = null
            val snapshot = algorithm.takeResult() ?: error("deep_sky_no_accepted_frames")
            checkCancelled()
            return OfflineDeepSkyResult(snapshot.image, snapshot.weights,
                OfflineDeepSkyStats(snapshot.stats.copy(estimatedWorkingBytes = memory.peakBytes),
                    source.frameCount, reference.frame, unknown, rejected.toMap(),
                    (System.nanoTime() - start) / 1_000_000, reference))
        } finally {
            referenceWorkspace.release()
            algorithm.reset()
        }
    }

    companion object {
        // UInt16 coverage keeps every count exact. Longer recordings fail before reading a frame.
        const val MAX_FRAMES = DeepSkyStacker.MAX_STACK_FRAMES.toLong()
        const val MAX_REFERENCE_SAMPLES = 256

        internal fun referenceIndices(frameCount: Long): List<Long> {
            require(frameCount > 0)
            val count = minOf(frameCount, MAX_REFERENCE_SAMPLES.toLong()).toInt()
            if (count == 1) return listOf(0L)
            return List(count) { index ->
                index.toLong() * (frameCount - 1L) / (count - 1L)
            }.distinct()
        }
    }
}
