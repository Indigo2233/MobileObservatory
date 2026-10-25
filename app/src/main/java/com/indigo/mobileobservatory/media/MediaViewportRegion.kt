package com.indigo.mobileobservatory.media

import kotlin.math.ceil
import kotlin.math.floor

/** A sampled viewport block plus a small margin. Allocation scales with screen size. */
object MediaViewportRegion {
    fun visible(width: Int, height: Int, viewWidth: Int, viewHeight: Int, scale: Float,
                panX: Float, panY: Float): MediaImageRegion? {
        if (viewWidth <= 0 || viewHeight <= 0 || !scale.isFinite() || scale <= 0) return null
        val left = (viewWidth - width * scale.toDouble()) / 2 + panX * viewWidth
        val top = (viewHeight - height * scale.toDouble()) / 2 + panY * viewHeight
        val x0 = floor(-left / scale).toLong().coerceIn(0, width.toLong())
        val y0 = floor(-top / scale).toLong().coerceIn(0, height.toLong())
        val x1 = ceil((viewWidth - left) / scale).toLong().coerceIn(0, width.toLong())
        val y1 = ceil((viewHeight - top) / scale).toLong().coerceIn(0, height.toLong())
        if (x1 <= x0 || y1 <= y0) return null
        var step = 1
        while (step <= 16384 && step * 2 <= 1.0 / scale) step *= 2
        val block = step * 128L
        val l = ((x0 / block - 1).coerceAtLeast(0) * block).toInt()
        val t = ((y0 / block - 1).coerceAtLeast(0) * block).toInt()
        val r = (((x1 + block - 1) / block + 1) * block).coerceAtMost(width.toLong()).toInt()
        val b = (((y1 + block - 1) / block + 1) * block).coerceAtMost(height.toLong()).toInt()
        return MediaImageRegion(l, t, r - l, b - t, step)
    }
}
