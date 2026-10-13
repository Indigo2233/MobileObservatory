package com.indigo.mobileobservatory.stacking

/** Inputs have already been calibrated in sensor space before demosaic and software binning. */
class LiveDeepSkyEngine(memoryBudgetBytes: Long) : LiveStackEngine {
    private val algorithm = DeepSkyStacker(DeepSkyConfig(memoryBudgetBytes = memoryBudgetBytes))

    override fun add(image: LinearImage, exposureSeconds: Double, cancelled: () -> Boolean): LiveFrameOutcome {
        val result = algorithm.add(image, exposureSeconds, cancelled)
        return LiveFrameOutcome(result.accepted, result.stats.stars, result.stats.registrationRmsPx,
            result.stats.rotationDegrees, result.reason?.name.orEmpty())
    }

    override fun snapshot(state: LiveStackState): LiveStackSnapshot? {
        val snapshot = algorithm.snapshot() ?: return null
        return LiveStackSnapshot(snapshot.image, snapshot.weights, state, mapOf(
            "algorithm" to "rigid_star_ransac_online_welford_sigma",
            "rejectedPixelSamples" to snapshot.stats.rejectedPixelSamples,
            "estimatedWorkingBytes" to snapshot.stats.estimatedWorkingBytes,
            "rejectionLimit" to DeepSkyStacker.REJECTION_LIMITATION
        ))
    }
}
