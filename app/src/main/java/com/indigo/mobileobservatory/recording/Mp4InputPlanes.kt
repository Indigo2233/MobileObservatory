package com.indigo.mobileobservatory.recording

import java.nio.ByteBuffer

/** Plane positions are relative to each buffer, even when U and V share interleaved storage. */
internal object Mp4InputPlanes {
    fun copy(source: ByteArray, sourceOffset: Int, sourceRowStride: Int, sourcePixelStride: Int,
             destination: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int) {
        require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0)
        require(sourceOffset >= 0 && sourceRowStride > 0 && sourcePixelStride > 0)
        val lastSource = sourceOffset.toLong() + (height - 1L) * sourceRowStride + (width - 1L) * sourcePixelStride
        val needed = (height - 1L) * rowStride + (width - 1L) * pixelStride + 1
        require(lastSource < source.size && needed <= destination.remaining()) { "Encoder YUV plane is truncated" }
        val buffer = destination.duplicate()
        val base = buffer.position()
        for (y in 0 until height) {
            val src = sourceOffset + y * sourceRowStride
            val dst = base + y * rowStride
            if (pixelStride == 1 && sourcePixelStride == 1) {
                buffer.position(dst)
                buffer.put(source, src, width)
            } else {
                for (x in 0 until width) buffer.put(dst + x * pixelStride, source[src + x * sourcePixelStride])
            }
        }
    }
}
