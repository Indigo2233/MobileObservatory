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
        // Fixed native extents keep the same demosaic buffers at edges and tail tiles.
        // Clamping the origin preserves physical-edge reflection and every tile's halo.
        val readWidth = minOf(sourceWidth, tileSide)
        val readHeight = minOf(sourceHeight, tileSide)
        val left = (tile.left - halo).coerceIn(0, sourceWidth - readWidth)
        val top = (tile.top - halo).coerceIn(0, sourceHeight - readHeight)
        return PixelRoi(left, top, readWidth, readHeight)
    }
    /**
     * Native extent that carries every tile read in one output row. Tiles in a row share their
     * vertical extent, so only the horizontal union is computed, and it is identical for every
     * row, which keeps the band buffer at one stable size for a whole frame. The decoder and the
     * memory budget both derive from this, so a band can never be narrower than a tile read.
     */
    fun bandRegion(oy: Int): PixelRoi {
        val h = kotlin.math.min(TILE, height - oy)
        val top = region.top + oy * softwareBin
        val span = TILE * softwareBin
        val first = readRegion(PixelRoi(region.left, top, span, h * softwareBin))
        // A short tail tile starts further right than region.right - span, so the last tile's own
        // origin is what fixes the band's right edge.
        val lastLeft = region.left + ((width - 1) / TILE) * TILE * softwareBin
        val last = readRegion(PixelRoi(lastLeft, top, span, h * softwareBin))
        val left = minOf(first.left, last.left)
        return PixelRoi(left, first.top, maxOf(first.right, last.right) - left, first.height)
    }
    val channels get() = if (format.isMono) 1 else 3
    private val tileSide get() = TILE * softwareBin + 2 * halo
    private val tilePixels get() = minOf(sourceWidth, tileSide).toLong() * minOf(sourceHeight, tileSide)
    private val sensorChannels get() = if (format.isRgb) 3 else 1
    val demosaicScratchBytes get() = 2 * LinearPixelDecoder.demosaicExtraBytes(minOf(sourceWidth, tileSide), minOf(sourceHeight, tileSide), format, algorithm)
    val calibrationOverheadBytes get() = calibrationBytes + if (calibrated) 2 * tilePixels * sensorChannels * 4 else 0L
    val overheadBytes: Long get() {
        val decode = tilePixels * (format.bytesPerPixel + 4L * sensorChannels + 4L * channels)
        val band = bandRegion(0)
        val bandBytes = band.width.toLong() * band.height * format.bytesPerPixel
        // Reserve old/new tile scratch and the reused band across dimension changes, not prompt
        // garbage collection. The band holds one row of tiles at native width.
        return calibrationOverheadBytes + 2 * decode + bandBytes + demosaicScratchBytes + 256 * 1024
    }
    internal fun deepSkyMemory() = OfflineDeepSkyMemory.estimate(width, height, channels, reusableInput = true,
        decoderWorkingBytes = overheadBytes - calibrationBytes, retainedCalibrationBytes = calibrationBytes)
    val workingBytes: Long get() = if (target == StackTarget.PLANETARY)
        overheadBytes + PlanetaryStacker.estimatedPeakBytes(width, height, channels, boundedDecode = true, reusableInput = true)
        else deepSkyMemory().peakBytes
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
