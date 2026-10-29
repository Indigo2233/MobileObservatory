package com.indigo.mobileobservatory.stacking

data class OfflineDeepSkyStats(
    val stack: DeepSkyStats,
    val inputFrames: Long,
    val referenceFrame: Long,
    val unknownExposureFrames: Long,
    val rejectionCounts: Map<DeepSkyRejection, Long>,
    val elapsedMillis: Long
)

data class OfflineDeepSkyResult(val image: LinearImage, val weights: FloatArray, val stats: OfflineDeepSkyStats)

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
        require(memory.peakBytes <= config.memoryBudgetBytes) { "memory_budget" }
        checkCancelled()
        val start = System.nanoTime()
        val algorithm = DeepSkyStacker(config, livePreviewOverlap = false, reusableOfflineInput = buffered != null)
        try {
            val input = if (buffered != null) FloatArray(source.width * source.height * source.channels) else null
            val rejected = linkedMapOf<DeepSkyRejection, Long>()
            var reference = -1L
            var unknown = 0L
            var index = 0L
            while (index < source.frameCount) {
                checkCancelled()
                val image = if (buffered == null) source.readFrame(index) else
                    buffered.readFrameInto(index, checkNotNull(input)).also {
                        require(it.pixels === input) { "source_buffer_changed" }
                    }
                checkCancelled()
                require(image.width == source.width && image.height == source.height && image.channels == source.channels) { "source_geometry_changed" }
                val exposure = source.exposureSeconds(index)
                val outcome = algorithm.add(image, exposure) { checkCancelled(); false }
                if (outcome.accepted) {
                    if (reference < 0) reference = index
                    if (exposure == 0.0) unknown++
                } else outcome.reason?.let { rejected[it] = (rejected[it] ?: 0L) + 1L }
                index++
                progress(index, source.frameCount, outcome.stats.copy(estimatedWorkingBytes = memory.peakBytes))
            }
            checkCancelled()
            val snapshot = algorithm.takeResult() ?: error("deep_sky_no_accepted_frames")
            checkCancelled()
            return OfflineDeepSkyResult(snapshot.image, snapshot.weights,
                OfflineDeepSkyStats(snapshot.stats.copy(estimatedWorkingBytes = memory.peakBytes), source.frameCount, reference, unknown, rejected.toMap(),
                    (System.nanoTime() - start) / 1_000_000))
        } finally { algorithm.reset() }
    }

    companion object {
        // Float coverage counts remain exact for every accepted frame and Int statistics
        // cannot overflow. A recording of arbitrary duration never allocates a frame list.
        const val MAX_FRAMES = 10_000_000L
    }
}
