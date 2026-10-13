package com.indigo.mobileobservatory.camera

import java.nio.ByteBuffer
import kotlin.math.max

data class PhoneMonoReduction(val sourceWidth: Int, val sourceHeight: Int, val blockSize: Int,
    val outputWidth: Int, val outputHeight: Int) {
    val coveredWidth get() = outputWidth * blockSize
    val coveredHeight get() = outputHeight * blockSize
    fun footprint(left: Double, top: Double, width: Double, height: Double, raw: Boolean = true): CameraPixelFootprint {
        val aspect = sourceWidth.toDouble() / sourceHeight
        val usedWidth = if (!raw && aspect <= width / height) height * aspect else width
        val usedHeight = if (!raw && aspect > width / height) width / aspect else height
        val contentLeft = left + (width - usedWidth) / 2
        val contentTop = top + (height - usedHeight) / 2
        return CameraPixelFootprint(contentLeft, contentTop, usedWidth * coveredWidth / sourceWidth,
            usedHeight * coveredHeight / sourceHeight,
            contentLeft + (blockSize - 1) * .5 * usedWidth / sourceWidth,
            contentTop + (blockSize - 1) * .5 * usedHeight / sourceHeight)
    }
}

/** Bounded row-at-a-time averaging; even RAW blocks always contain complete Bayer periods. */
object PhoneMonoFrameConverter {
    fun chooseStreamIndex(sizes: List<Pair<Int, Int>>, maxLongSide: Int): Int? {
        require(maxLongSide > 0)
        val valid = sizes.indices.filter { sizes[it].first > 0 && sizes[it].second > 0 }
        val capped = valid.filter { maxOf(sizes[it].first, sizes[it].second) <= maxLongSide }
        fun area(index: Int) = sizes[index].first.toLong() * sizes[index].second
        return if (capped.isNotEmpty()) capped.maxByOrNull(::area) else valid.minByOrNull(::area)
    }
    fun plan(width: Int, height: Int, maxLongSide: Int, raw: Boolean): PhoneMonoReduction {
        require(width > 0 && height > 0 && maxLongSide > 0)
        val needed = (maxOf(width, height).toLong() + maxLongSide - 1) / maxLongSide
        val block = if (raw) max(2L, (needed + 1) / 2 * 2) else max(1L, needed)
        require(block <= Int.MAX_VALUE)
        val outW = width / block.toInt(); val outH = height / block.toInt()
        require(outW > 0 && outH > 0) { "No complete image blocks at this resolution" }
        PhoneSkyResourceBudget.frameBytes(outW, outH, if (raw) 2 else 1)
        return PhoneMonoReduction(width, height, block.toInt(), outW, outH)
    }

    fun convert(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, plan: PhoneMonoReduction,
                raw: Boolean, frameId: Long, timestamp: Long, checkCancelled: () -> Unit = {}): FrameData {
        val sampleBytes = if (raw) 2 else 1
        require(pixelStride >= sampleBytes && rowStride > 0)
        val rowBytes = (plan.coveredWidth.toLong() - 1) * pixelStride + sampleBytes
        val required = (plan.coveredHeight.toLong() - 1) * rowStride + rowBytes
        require(rowBytes <= rowStride && required <= buffer.remaining()) { "Truncated camera image plane" }
        val outBytes = PhoneSkyResourceBudget.frameBytes(plan.outputWidth, plan.outputHeight, sampleBytes)
        PhoneSkyResourceBudget.requireHeap(outBytes + rowBytes + plan.outputWidth.toLong() * 8)
        val input = buffer.slice() // Neither DNG pixels nor the producer's buffer position are modified.
        val out = ByteArray(outBytes.toInt()); val row = ByteArray(rowBytes.toInt())
        val sums = LongArray(plan.outputWidth)
        val count = plan.blockSize.toLong() * plan.blockSize
        for (outY in 0 until plan.outputHeight) {
            sums.fill(0)
            for (dy in 0 until plan.blockSize) {
                checkCancelled()
                val sourceY = outY * plan.blockSize + dy
                input.position(sourceY * rowStride); input.get(row)
                for (x in 0 until plan.coveredWidth) {
                    val offset = x * pixelStride
                    val value = if (raw) (row[offset].toInt() and 255) or ((row[offset + 1].toInt() and 255) shl 8)
                        else row[offset].toInt() and 255
                    val target = x / plan.blockSize
                    sums[target] = sums[target] + value.toLong()
                }
            }
            for (x in 0 until plan.outputWidth) {
                val value = (sums[x] / count).toInt()
                val output = (outY * plan.outputWidth + x) * sampleBytes
                out[output] = value.toByte()
                if (raw) out[output + 1] = (value ushr 8).toByte()
            }
        }
        return FrameData(out, plan.outputWidth, plan.outputHeight, if (raw) PixelFormat.MONO16 else PixelFormat.MONO8, frameId, timestamp)
    }
}
