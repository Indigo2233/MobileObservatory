package com.indigo.mobileobservatory.stacking

/** Display samples only. Accumulators and saved scientific images retain native resolution. */
internal data class StackPreviewGrid(val sourceWidth: Int, val sourceHeight: Int, val maxSide: Int) {
    init { require(sourceWidth > 0 && sourceHeight > 0 && maxSide > 0) }
    val step = ((maxOf(sourceWidth, sourceHeight).toLong() + maxSide - 1) / maxSide).toInt().coerceAtLeast(1)
    val width = (sourceWidth + step - 1) / step
    val height = (sourceHeight + step - 1) / step
    fun sourcePixel(pixel: Int) = (pixel / width * step) * sourceWidth + pixel % width * step

    companion object {
        fun workingBytes(width: Int, height: Int, channels: Int, maxSide: Int): Long {
            val grid = StackPreviewGrid(width, height, maxSide)
            val pixels = grid.width.toLong() * grid.height
            // Renderer input, latest snapshot and replacement; four filter planes,
            // old/new ARGB previews and one conversion row.
            return pixels * (3L * (channels * 4L + 4) + 16 + 8) +
                grid.width * (channels * 4L + 4)
        }
    }
}
