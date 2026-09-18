package com.indigo.mobileobservatory.camera

/**
 * Preview bitmaps are ARGB8888. A 9576×6388 frame is ~245MB × 3 buffers and will
 * OOM / render as a 1×1 black image. Downsample for display only; capture/ROI stay full size.
 */
internal object PreviewScale {
    const val MAX_PIXELS = 4_194_304L

    fun sampleStep(width: Int, height: Int, keepBayerPhase: Boolean): Int {
        val pixels = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)
        if (pixels <= MAX_PIXELS) return 1
        val inc = if (keepBayerPhase) 2 else 1
        var step = inc.coerceAtLeast(1)
        while (step < 16 && (width / step).toLong() * (height / step) > MAX_PIXELS) {
            step += inc
        }
        return step
    }

    fun subsample(
        src: ByteArray,
        width: Int,
        height: Int,
        bytesPerPixel: Int,
        step: Int,
        dst: ByteArray
    ): Pair<Int, Int> {
        val bpp = bytesPerPixel.coerceIn(1, 6)
        val s = step.coerceAtLeast(1)
        var outW = (width / s).coerceAtLeast(1)
        var outH = (height / s).coerceAtLeast(1)
        if (bpp == 1 || bpp == 2) {
            outW = outW and 1.inv()
            outH = outH and 1.inv()
            if (outW < 2) outW = (width / s).coerceAtLeast(1)
            if (outH < 2) outH = (height / s).coerceAtLeast(1)
        }
        val needed = outW * outH * bpp
        require(dst.size >= needed)
        var di = 0
        for (y in 0 until outH) {
            val srcRow = y * s * width * bpp
            for (x in 0 until outW) {
                val si = srcRow + x * s * bpp
                if (si + bpp > src.size) return outW to outH
                src.copyInto(dst, di, si, si + bpp)
                di += bpp
            }
        }
        return outW to outH
    }
}
