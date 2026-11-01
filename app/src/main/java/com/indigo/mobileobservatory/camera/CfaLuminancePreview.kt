package com.indigo.mobileobservatory.camera

import java.nio.ByteOrder
import kotlin.math.min

/** Display-only source-cell means; large frames sample the composed luminance into a bounded display grid. */
internal object CfaLuminancePreview {
    fun compose(frame: FrameData, destination: ByteArray, byteOrder: ByteOrder, sampleStep: Int = 1): FrameData {
        require(frame.pixelFormat.isBayer && frame.width > 0 && frame.height > 0)
        require(sampleStep >= 1)
        val bytesPerPixel = frame.pixelFormat.bytesPerPixel
        val outputWidth = (frame.width / sampleStep).coerceAtLeast(1)
        val outputHeight = (frame.height / sampleStep).coerceAtLeast(1)
        require(frame.width.toLong() * frame.height * bytesPerPixel <= frame.data.size)
        require(outputWidth.toLong() * outputHeight * bytesPerPixel <= destination.size)
        require(frame.data !== destination)
        fun read(x: Int, y: Int): Int {
            val index = (y * frame.width + x) * bytesPerPixel
            val first = frame.data[index].toInt() and 255
            if (bytesPerPixel == 1) return first
            val second = frame.data[index + 1].toInt() and 255
            return if (byteOrder == ByteOrder.BIG_ENDIAN) (first shl 8) or second else first or (second shl 8)
        }
        fun write(mean: Int, index: Int) {
            if (bytesPerPixel == 1) destination[index] = mean.toByte()
            else if (byteOrder == ByteOrder.BIG_ENDIAN) {
                destination[index] = (mean ushr 8).toByte(); destination[index + 1] = mean.toByte()
            } else {
                destination[index] = mean.toByte(); destination[index + 1] = (mean ushr 8).toByte()
            }
        }
        val columns = min(2, frame.width); val rows = min(2, frame.height)
        if (sampleStep == 1) {
            // Every composed cell fills a 2x2 block of the full-resolution luminance plane.
            for (cellY in 0 until (frame.height + rows - 1) / rows) {
                val sourceY = min(cellY * rows, frame.height - rows)
                for (cellX in 0 until (frame.width + columns - 1) / columns) {
                    val sourceX = min(cellX * columns, frame.width - columns)
                    var sum = 0
                    for (dy in 0 until rows) for (dx in 0 until columns) sum += read(sourceX + dx, sourceY + dy)
                    val mean = (sum + columns * rows / 2) / (columns * rows)
                    for (dy in 0 until min(rows, outputHeight - cellY * rows))
                        for (dx in 0 until min(columns, outputWidth - cellX * columns))
                            write(mean, ((cellY * rows + dy) * outputWidth + cellX * columns + dx) * bytesPerPixel)
                }
            }
        } else {
            // Average every source cell the output pixel covers. Point sampling would drop
            // the rest of the covered area, which shatters a star into a hard block or
            // removes it entirely; PHD2's bilinear rescale spreads that energy instead
            // (src/guider.cpp, Guider::PaintHelper).
            for (y in 0 until outputHeight) {
                val firstCellRow = (y * sampleStep) / rows
                val lastCellRow = (min((y + 1) * sampleStep, frame.height) + rows - 1) / rows
                for (x in 0 until outputWidth) {
                    val firstCellCol = (x * sampleStep) / columns
                    val lastCellCol = (min((x + 1) * sampleStep, frame.width) + columns - 1) / columns
                    var sum = 0L; var count = 0
                    for (cellY in firstCellRow until lastCellRow) {
                        val sourceY = min(cellY * rows, frame.height - rows)
                        for (cellX in firstCellCol until lastCellCol) {
                            val sourceX = min(cellX * columns, frame.width - columns)
                            for (dy in 0 until rows) for (dx in 0 until columns) {
                                sum += read(sourceX + dx, sourceY + dy); count++
                            }
                        }
                    }
                    val mean = if (count == 0) 0 else (sum + count / 2) / count
                    write(mean.toInt(), (y * outputWidth + x) * bytesPerPixel)
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
        // The destination is a reused buffer that may be larger than this grid.
        // FrameData carries the real grid size, so the surplus bytes are ignored.
        return frame.copy(data = destination, width = outputWidth, height = outputHeight, pixelFormat = format)
    }
}
