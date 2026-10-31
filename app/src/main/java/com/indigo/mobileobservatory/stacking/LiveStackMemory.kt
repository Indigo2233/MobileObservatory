package com.indigo.mobileobservatory.stacking

/** Matches the tiled decoder, one admitted raw packet and bounded display snapshots.
 * Export runs on the science worker with ingestion held and decoder storage released. */
internal object LiveStackMemory {
    const val PREVIEW_MAX_SIDE = 768
    const val MAX_IN_FLIGHT_FRAMES = 1
    const val HEADROOM = 8L * 1024 * 1024

    fun workingBytes(key: StackSessionKey, calibrationBytes: Long, calibrated: Boolean): Long {
        val plan = LiveFrameDecoder.plan(key, calibrationBytes, calibrated)
        val channels = plan.channels
        val pixels = plan.width.toLong() * plan.height
        val raw = key.width.toLong() * key.height * key.format.bytesPerPixel * MAX_IN_FLIGHT_FRAMES
        val engine = if (key.target == StackTarget.PLANETARY)
            LivePlanetaryEngine.estimateWorkingBytes(plan.width, plan.height, channels, PREVIEW_MAX_SIDE)
        else DeepSkyStacker.estimateWorkingBytes(plan.width, plan.height, channels, PREVIEW_MAX_SIDE)
        val stacking = engine + raw + plan.overheadBytes
        val preview = StackPreviewGrid.workingBytes(plan.width, plan.height, channels, PREVIEW_MAX_SIDE)
        // Deep-sky export borrows mean/coverage. Planetary export normalizes Double sums into
        // one Float RGB result, while retaining its registration and accumulation workspaces.
        val retainedEngine = if (key.target == StackTarget.PLANETARY)
            pixels * (channels * 8L + 12) + LivePlanetaryEngine.meshBytes(plan.width, plan.height)
        else pixels * (channels * 8L + 4)
        val exportImage = if (key.target == StackTarget.PLANETARY) pixels * channels * 4L else 0L
        val export = retainedEngine + exportImage + pixels * 16 + calibrationBytes + preview + HEADROOM
        return maxOf(stacking, export)
    }
}
