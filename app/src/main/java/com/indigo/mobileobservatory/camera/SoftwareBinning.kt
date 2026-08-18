package com.indigo.mobileobservatory.camera

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-side NxN average binning used when the camera cannot do the requested factor.
 */
class SoftwareBinning(
    poolCapacity: Int = 8
) {
    private val pool = ReusableByteArrayPool(poolCapacity)
    private val owned = Collections.newSetFromMap(ConcurrentHashMap<ByteArray, Boolean>())

    @Volatile
    var factor: Int = 1

    fun apply(frame: FrameData): FrameData {
        val bin = factor
        if (bin <= 1) return frame
        val outW = frame.width / bin
        val outH = frame.height / bin
        if (outW < 1 || outH < 1) return frame
        val bpp = frame.pixelFormat.bytesPerPixel
        val outSize = outW * outH * bpp
        val dst = pool.acquire(outSize)
        owned.add(dst)
        when (bpp) {
            1 -> binMono8(frame.data, frame.width, dst, outW, outH, bin)
            2 -> binMono16(frame.data, frame.width, dst, outW, outH, bin)
            3 -> binRgb24(frame.data, frame.width, dst, outW, outH, bin)
            6 -> binRgb48(frame.data, frame.width, dst, outW, outH, bin)
            else -> {
                owned.remove(dst)
                pool.release(dst)
                return frame
            }
        }
        return frame.copy(data = dst, width = outW, height = outH)
    }

    fun release(buffer: ByteArray): Boolean {
        if (!owned.remove(buffer)) return false
        pool.release(buffer)
        return true
    }

    companion object {
        internal fun binMono8(
            src: ByteArray, srcW: Int, dst: ByteArray, outW: Int, outH: Int, bin: Int
        ) {
            val area = bin * bin
            var di = 0
            for (oy in 0 until outH) {
                val row0 = oy * bin
                for (ox in 0 until outW) {
                    val col0 = ox * bin
                    var sum = 0
                    for (by in 0 until bin) {
                        val row = (row0 + by) * srcW + col0
                        for (bx in 0 until bin) {
                            sum += src[row + bx].toInt() and 0xFF
                        }
                    }
                    dst[di++] = (sum / area).toByte()
                }
            }
        }

        internal fun binMono16(
            src: ByteArray, srcW: Int, dst: ByteArray, outW: Int, outH: Int, bin: Int
        ) {
            val area = bin * bin
            var di = 0
            for (oy in 0 until outH) {
                val row0 = oy * bin
                for (ox in 0 until outW) {
                    val col0 = ox * bin
                    var sum = 0
                    for (by in 0 until bin) {
                        var si = ((row0 + by) * srcW + col0) * 2
                        for (bx in 0 until bin) {
                            val lo = src[si].toInt() and 0xFF
                            val hi = src[si + 1].toInt() and 0xFF
                            sum += lo or (hi shl 8)
                            si += 2
                        }
                    }
                    val avg = sum / area
                    dst[di++] = (avg and 0xFF).toByte()
                    dst[di++] = ((avg shr 8) and 0xFF).toByte()
                }
            }
        }

        internal fun binRgb24(
            src: ByteArray, srcW: Int, dst: ByteArray, outW: Int, outH: Int, bin: Int
        ) {
            val area = bin * bin
            var di = 0
            for (oy in 0 until outH) {
                val row0 = oy * bin
                for (ox in 0 until outW) {
                    val col0 = ox * bin
                    var r = 0
                    var g = 0
                    var b = 0
                    for (by in 0 until bin) {
                        var si = ((row0 + by) * srcW + col0) * 3
                        for (bx in 0 until bin) {
                            r += src[si].toInt() and 0xFF
                            g += src[si + 1].toInt() and 0xFF
                            b += src[si + 2].toInt() and 0xFF
                            si += 3
                        }
                    }
                    dst[di++] = (r / area).toByte()
                    dst[di++] = (g / area).toByte()
                    dst[di++] = (b / area).toByte()
                }
            }
        }

        internal fun binRgb48(
            src: ByteArray, srcW: Int, dst: ByteArray, outW: Int, outH: Int, bin: Int
        ) {
            val area = bin * bin
            var di = 0
            for (oy in 0 until outH) {
                val row0 = oy * bin
                for (ox in 0 until outW) {
                    val col0 = ox * bin
                    var r = 0
                    var g = 0
                    var b = 0
                    for (by in 0 until bin) {
                        var si = ((row0 + by) * srcW + col0) * 6
                        for (bx in 0 until bin) {
                            r += (src[si].toInt() and 0xFF) or ((src[si + 1].toInt() and 0xFF) shl 8)
                            g += (src[si + 2].toInt() and 0xFF) or ((src[si + 3].toInt() and 0xFF) shl 8)
                            b += (src[si + 4].toInt() and 0xFF) or ((src[si + 5].toInt() and 0xFF) shl 8)
                            si += 6
                        }
                    }
                    val ra = r / area
                    val ga = g / area
                    val ba = b / area
                    dst[di++] = (ra and 0xFF).toByte()
                    dst[di++] = ((ra shr 8) and 0xFF).toByte()
                    dst[di++] = (ga and 0xFF).toByte()
                    dst[di++] = ((ga shr 8) and 0xFF).toByte()
                    dst[di++] = (ba and 0xFF).toByte()
                    dst[di++] = ((ba shr 8) and 0xFF).toByte()
                }
            }
        }
    }
}
