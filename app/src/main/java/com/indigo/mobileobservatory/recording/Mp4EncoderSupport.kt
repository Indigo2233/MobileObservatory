package com.indigo.mobileobservatory.recording

internal object Mp4EncoderSupport {
    const val MIN_SIDE = 16
    const val MAX_BITRATE = 16_000_000
    const val MIN_BITRATE = 250_000

    fun align16(n: Int): Int {
        if (n <= 0) return MIN_SIDE
        return (n + 15) and 0x7FFFFFF0
    }

    fun bitrate(width: Int, height: Int, fps: Int, minBitrate: Int = MIN_BITRATE, maxBitrate: Int = MAX_BITRATE): Int {
        val raw = width.toLong() * height.toLong() * fps.coerceIn(1, 60).toLong() / 4L
        return raw.coerceIn(minBitrate.toLong(), maxBitrate.toLong()).toInt()
    }

    /**
     * Pick a 16-aligned size the encoder accepts, shrinking from the source if needed.
     * Returns null when even the minimum size is rejected.
     */
    fun fitSize(
        srcW: Int,
        srcH: Int,
        isSizeSupported: (Int, Int) -> Boolean
    ): Pair<Int, Int>? {
        var w = align16(srcW)
        var h = align16(srcH)
        repeat(12) {
            if (w < MIN_SIDE || h < MIN_SIDE) return null
            if (isSizeSupported(w, h)) return w to h
            w = align16((w * 3) / 4)
            h = align16((h * 3) / 4)
        }
        return if (isSizeSupported(MIN_SIDE, MIN_SIDE)) MIN_SIDE to MIN_SIDE else null
    }
}
