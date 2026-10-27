package com.indigo.mobileobservatory.camera

import java.nio.ByteOrder
import kotlin.math.min

/** Display-only cell means, expanded onto the same image grid to retain sensor coordinates. */
internal object CfaLuminancePreview {
    fun compose(frame: FrameData, destination: ByteArray, byteOrder: ByteOrder): FrameData {
        require(frame.pixelFormat.isBayer && frame.width > 0 && frame.height > 0)
        val bytesPerPixel = frame.pixelFormat.bytesPerPixel
        val needed = frame.width.toLong() * frame.height * bytesPerPixel
        require(needed <= frame.data.size && needed <= destination.size)
        require(frame.data !== destination)
        val columns = min(2, frame.width); val rows = min(2, frame.height)
        fun read(x: Int, y: Int): Int {
            val index = (y * frame.width + x) * bytesPerPixel
            val first = frame.data[index].toInt() and 255
            if (bytesPerPixel == 1) return first
            val second = frame.data[index + 1].toInt() and 255
            return if (byteOrder == ByteOrder.BIG_ENDIAN) (first shl 8) or second else first or (second shl 8)
        }
        for (y in 0 until frame.height step 2) for (x in 0 until frame.width step 2) {
            // An odd trailing row/column uses the nearest complete cell, retaining all CFA phases.
            val sourceX = min(x, frame.width - columns)
            val sourceY = min(y, frame.height - rows)
            var sum = 0
            for (dy in 0 until rows) for (dx in 0 until columns) sum += read(sourceX + dx, sourceY + dy)
            val count = columns * rows
            val mean = (sum + count / 2) / count
            for (dy in 0 until min(2, frame.height - y)) for (dx in 0 until min(2, frame.width - x)) {
                val index = ((y + dy) * frame.width + x + dx) * bytesPerPixel
                if (bytesPerPixel == 1) destination[index] = mean.toByte()
                else if (byteOrder == ByteOrder.BIG_ENDIAN) {
                    destination[index] = (mean ushr 8).toByte(); destination[index + 1] = mean.toByte()
                } else {
                    destination[index] = mean.toByte(); destination[index + 1] = (mean ushr 8).toByte()
                }
            }
        }
        val format = when (frame.pixelFormat.nativeBits) {
            8 -> PixelFormat.MONO8
            10 -> PixelFormat.MONO10
            12 -> PixelFormat.MONO12
            14 -> PixelFormat.MONO14
            else -> PixelFormat.MONO16
        }
        return frame.copy(data = destination, pixelFormat = format)
    }
}
