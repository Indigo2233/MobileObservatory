package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.PixelFormat
import kotlin.math.floor

/** Science reads a bounded native region, calibrates it, demosaics with a halo, then trims.
 * Full-file dimensions are metadata only. No resize or display pixels enter the stack. */
data class OfflineRegionPlan(
    val sourceWidth: Int, val sourceHeight: Int, val format: PixelFormat,
    val region: PixelRoi, val algorithm: DemosaicAlgorithm, val target: StackTarget,
    val softwareBin: Int = 1,
    val calibrationBytes: Long = 0, val calibrated: Boolean = false
) {
    init { region.checked(sourceWidth, sourceHeight); require(softwareBin in 1..4) }
    val width get() = region.width / softwareBin
    val height get() = region.height / softwareBin
    // DDFAPD's successive directional/refinement passes have finite support <16px.
    private val halo get() = if (!format.isBayer) 0 else when (algorithm) {
            DemosaicAlgorithm.DDFAPD -> 16
            DemosaicAlgorithm.VNG -> 4
            DemosaicAlgorithm.BILINEAR -> 2
        }
    fun readRegion(tile: PixelRoi): PixelRoi {
        val left = (tile.left - halo).coerceAtLeast(0)
        val top = (tile.top - halo).coerceAtLeast(0)
        val right = (tile.right.toLong() + halo).coerceAtMost(sourceWidth.toLong()).toInt()
        val bottom = (tile.bottom.toLong() + halo).coerceAtMost(sourceHeight.toLong()).toInt()
        return PixelRoi(left, top, right - left, bottom - top)
    }
    val channels get() = if (format.isMono) 1 else 3
    private val tileSide get() = TILE * softwareBin + 2 * halo
    private val tilePixels get() = minOf(sourceWidth, tileSide).toLong() * minOf(sourceHeight, tileSide)
    private val sensorChannels get() = if (format.isRgb) 3 else 1
    val demosaicScratchBytes get() = 2 * LinearPixelDecoder.demosaicExtraBytes(minOf(sourceWidth, tileSide), minOf(sourceHeight, tileSide), format, algorithm)
    val calibrationOverheadBytes get() = calibrationBytes + if (calibrated) 2 * tilePixels * sensorChannels * 4 else 0L
    val overheadBytes: Long get() {
        val decode = tilePixels * (format.bytesPerPixel + 4L * sensorChannels + 4L * channels)
        // Reserve old/new tile scratch across dimension changes, not prompt garbage collection.
        return calibrationOverheadBytes + 2 * decode + demosaicScratchBytes + 256 * 1024
    }
    val workingBytes: Long get() = overheadBytes + if (target == StackTarget.PLANETARY)
        PlanetaryStacker.estimatedPeakBytes(width, height, channels, boundedDecode = true)
        else DeepSkyStacker.estimateOfflineWorkingBytes(width, height, channels)
    fun issue(budget: Long): String? = when {
        width < 12 || height < 12 -> "invalid_roi"
        width.toLong() * height > LinearImage.MAX_PIXELS -> "processing_size"
        workingBytes > budget -> "offline_memory_budget"
        else -> null
    }
    /** Keep the user's aspect and centre; reserve a worst-case interior halo so moving the
     * suggested box cannot unexpectedly exceed its budget at an image edge. */
    fun suggested(budget: Long): PixelRoi? {
        val ratio = region.width.toDouble() / region.height
        val cx = region.left + region.width / 2.0; val cy = region.top + region.height / 2.0
        var lo = maxOf(12 * softwareBin, kotlin.math.ceil(12 * softwareBin * ratio).toInt())
        var hi = minOf(sourceWidth, floor(sourceHeight * ratio).toInt())
        var best: PixelRoi? = null
        while (lo <= hi) {
            val width = lo + (hi - lo) / 2
            val height = floor(width / ratio + 1e-9).toInt().coerceAtLeast(12 * softwareBin)
            val left = (cx - width / 2).toInt().coerceIn(0, sourceWidth - width)
            val top = (cy - height / 2).toInt().coerceIn(0, sourceHeight - height)
            val candidate = PixelRoi(left, top, width, height)
            val interior = candidate.copy(left = (sourceWidth - width) / 2, top = (sourceHeight - height) / 2)
            if (copy(region = interior).issue(budget) == null) { best = candidate; lo = width + 1 } else hi = width - 1
        }
        return best
    }
    companion object { const val TILE = 64 }
}
