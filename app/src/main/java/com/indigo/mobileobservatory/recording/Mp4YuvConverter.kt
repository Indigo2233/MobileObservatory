package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.stacking.ChannelOrder
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import java.nio.ByteOrder

/**
 * Converts science frames into tightly packed NV12 (Y plane + interleaved UV).
 * Used by [Mp4Writer] before copying into a MediaCodec input Image or ByteBuffer.
 */
class Mp4YuvConverter {
    private var grey8: ByteArray = ByteArray(0)
    private var srcY: ByteArray = ByteArray(0)
    private var srcUv: ByteArray = ByteArray(0)
    private var dstY: ByteArray = ByteArray(0)
    private var dstUv: ByteArray = ByteArray(0)

    var wbRedGain = 1.0f
    var wbGreenGain = 1.0f
    var wbBlueGain = 1.0f

    fun convert(frame: FrameData, outW: Int, outH: Int, traits: PixelTraits? = null): Nv12Frame {
        require(outW > 0 && outH > 0)
        val srcW = frame.width
        val srcH = frame.height
        ensure(srcY, srcW * srcH).also { srcY = it }
        ensure(srcUv, srcW * ((srcH + 1) / 2)).also { srcUv = it }
        srcUv.fill(128.toByte())

        if (frame.pixelFormat.isRgb) {
            fillRgbNv12(frame, srcY, srcUv, traits)
        } else if (frame.pixelFormat.isBayer) {
            fillBayerNv12(frame, srcY, srcUv, traits)
        } else {
            fillMonoY(frame, srcY, traits)
        }

        if (srcW == outW && srcH == outH) {
            return Nv12Frame(srcY, srcUv, outW, outH)
        }

        ensure(dstY, outW * outH).also { dstY = it }
        ensure(dstUv, outW * ((outH + 1) / 2)).also { dstUv = it }
        scaleNv12(srcY, srcUv, srcW, srcH, dstY, dstUv, outW, outH)
        return Nv12Frame(dstY, dstUv, outW, outH)
    }

    private fun displayShift(frame: FrameData, traits: PixelTraits?): Int {
        if (!frame.pixelFormat.isHighBit) return 0
        return if (traits?.alignment == SampleAlignment.LEFT) 8
        else ((traits?.validBits ?: frame.pixelFormat.nativeBits) - 8).coerceAtLeast(0)
    }

    private fun sample8(data: ByteArray, index: Int, highBit: Boolean, shift: Int, bigEndian: Boolean): Int {
        val offset = index * if (highBit) 2 else 1
        if (offset < 0 || offset >= data.size) return 0
        if (!highBit) return data[offset].toInt() and 255
        if (offset + 1 >= data.size) return 0
        val a = data[offset].toInt() and 255
        val b = data[offset + 1].toInt() and 255
        val value = if (bigEndian) (a shl 8) or b else a or (b shl 8)
        return (value ushr shift).coerceIn(0, 255)
    }

    private fun fillRgbNv12(frame: FrameData, yOut: ByteArray, uvOut: ByteArray, traits: PixelTraits?) {
        val high = frame.pixelFormat.isHighBit
        val shift = displayShift(frame, traits)
        val bigEndian = traits?.byteOrder == ByteOrder.BIG_ENDIAN
        val bgr = traits?.channelOrder == ChannelOrder.BGR
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val base = (y * frame.width + x) * 3
                val r = (sample8(frame.data, base + if (bgr) 2 else 0, high, shift, bigEndian) * wbRedGain).toInt().coerceIn(0, 255)
                val g = (sample8(frame.data, base + 1, high, shift, bigEndian) * wbGreenGain).toInt().coerceIn(0, 255)
                val b = (sample8(frame.data, base + if (bgr) 0 else 2, high, shift, bigEndian) * wbBlueGain).toInt().coerceIn(0, 255)
                yOut[y * frame.width + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235).toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    val index = (y / 2) * frame.width + x
                    if (index + 1 < uvOut.size) {
                        uvOut[index] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240).toByte()
                        uvOut[index + 1] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240).toByte()
                    }
                }
            }
        }
    }

    private fun fillMonoY(frame: FrameData, yOut: ByteArray, traits: PixelTraits?) {
        val src = frame.data
        val w = frame.width
        val h = frame.height
        val isHigh = frame.pixelFormat.isHighBit
        val shift = displayShift(frame, traits)
        val bigEndian = traits?.byteOrder == ByteOrder.BIG_ENDIAN
        val pixels = w * h
        if (isHigh) {
            for (i in 0 until pixels) {
                yOut[i] = sample8(src, i, true, shift, bigEndian).toByte()
            }
        } else {
            val copyLen = pixels.coerceAtMost(src.size).coerceAtMost(yOut.size)
            System.arraycopy(src, 0, yOut, 0, copyLen)
            if (copyLen < pixels) yOut.fill(0, copyLen, pixels)
        }
    }

    private fun fillBayerNv12(frame: FrameData, yOut: ByteArray, uvOut: ByteArray, traits: PixelTraits?) {
        val src = frame.data
        val w = frame.width
        val h = frame.height
        val is10 = frame.pixelFormat.isHighBit
        val shf = displayShift(frame, traits)
        val bigEndian = traits?.byteOrder == ByteOrder.BIG_ENDIAN
        ensure(grey8, w * h).also { grey8 = it }

        if (is10) {
            for (i in 0 until w * h) {
                grey8[i] = sample8(src, i, true, shf, bigEndian).toByte()
            }
        } else {
            val copyLen = (w * h).coerceAtMost(src.size)
            System.arraycopy(src, 0, grey8, 0, copyLen)
            if (copyLen < w * h) grey8.fill(0, copyLen, w * h)
        }

        val pfName = frame.pixelFormat.name
        val rX: Int
        val rY: Int
        when {
            pfName.startsWith("BAYER_RG") -> { rX = 0; rY = 0 }
            pfName.startsWith("BAYER_GR") -> { rX = 1; rY = 0 }
            pfName.startsWith("BAYER_GB") -> { rX = 0; rY = 1 }
            else -> { rX = 1; rY = 1 }
        }

        val wR = wbRedGain
        val wG = wbGreenGain
        val wB = wbBlueGain
        uvOut.fill(128.toByte())

        for (y in 0 until h) {
            val yEven = y % 2 == 0
            val ym = if (y > 0) y - 1 else 0
            val yp = if (y < h - 1) y + 1 else h - 1
            for (x in 0 until w) {
                val bx = x % 2
                val by = y % 2
                val cur = grey8[y * w + x].toInt() and 0xFF
                val xm = if (x > 0) x - 1 else 0
                val xp = if (x < w - 1) x + 1 else w - 1
                val r: Int
                val g: Int
                val b: Int
                when {
                    bx == rX && by == rY -> {
                        r = cur
                        g = ((grey8[y * w + xm].toInt() and 0xFF) + (grey8[y * w + xp].toInt() and 0xFF) +
                            (grey8[ym * w + x].toInt() and 0xFF) + (grey8[yp * w + x].toInt() and 0xFF)) shr 2
                        b = ((grey8[ym * w + xm].toInt() and 0xFF) + (grey8[ym * w + xp].toInt() and 0xFF) +
                            (grey8[yp * w + xm].toInt() and 0xFF) + (grey8[yp * w + xp].toInt() and 0xFF)) shr 2
                    }
                    bx != rX && by != rY -> {
                        b = cur
                        g = ((grey8[y * w + xm].toInt() and 0xFF) + (grey8[y * w + xp].toInt() and 0xFF) +
                            (grey8[ym * w + x].toInt() and 0xFF) + (grey8[yp * w + x].toInt() and 0xFF)) shr 2
                        r = ((grey8[ym * w + xm].toInt() and 0xFF) + (grey8[ym * w + xp].toInt() and 0xFF) +
                            (grey8[yp * w + xm].toInt() and 0xFF) + (grey8[yp * w + xp].toInt() and 0xFF)) shr 2
                    }
                    else -> {
                        g = cur
                        if (by == rY) {
                            r = ((grey8[y * w + xm].toInt() and 0xFF) + (grey8[y * w + xp].toInt() and 0xFF)) shr 1
                            b = ((grey8[ym * w + x].toInt() and 0xFF) + (grey8[yp * w + x].toInt() and 0xFF)) shr 1
                        } else {
                            b = ((grey8[y * w + xm].toInt() and 0xFF) + (grey8[y * w + xp].toInt() and 0xFF)) shr 1
                            r = ((grey8[ym * w + x].toInt() and 0xFF) + (grey8[yp * w + x].toInt() and 0xFF)) shr 1
                        }
                    }
                }
                val r8 = (r * wR).toInt().coerceIn(0, 255)
                val g8 = (g * wG).toInt().coerceIn(0, 255)
                val b8 = (b * wB).toInt().coerceIn(0, 255)
                yOut[y * w + x] = (((66 * r8 + 129 * g8 + 25 * b8 + 128) shr 8) + 16).coerceIn(16, 235).toByte()
                if (yEven && x % 2 == 0) {
                    val u = (((-38 * r8 - 74 * g8 + 112 * b8 + 128) shr 8) + 128).coerceIn(16, 240)
                    val v = (((112 * r8 - 94 * g8 - 18 * b8 + 128) shr 8) + 128).coerceIn(16, 240)
                    val uvOff = (y / 2) * w + x
                    if (uvOff + 1 < uvOut.size) {
                        uvOut[uvOff] = u.toByte()
                        uvOut[uvOff + 1] = v.toByte()
                    }
                }
            }
        }
    }

    private fun scaleNv12(
        srcY: ByteArray,
        srcUv: ByteArray,
        srcW: Int,
        srcH: Int,
        dstY: ByteArray,
        dstUv: ByteArray,
        dstW: Int,
        dstH: Int
    ) {
        for (y in 0 until dstH) {
            val sy = (y * srcH) / dstH
            val srcRow = sy * srcW
            val dstRow = y * dstW
            for (x in 0 until dstW) {
                val sx = (x * srcW) / dstW
                dstY[dstRow + x] = srcY[srcRow + sx]
            }
        }
        val dstUvH = (dstH + 1) / 2
        val srcUvH = (srcH + 1) / 2
        dstUv.fill(128.toByte())
        for (y in 0 until dstUvH) {
            val sy = ((y * srcUvH) / dstUvH).coerceAtMost(srcUvH - 1)
            for (x in 0 until dstW step 2) {
                val sx = ((x * srcW) / dstW).and(0x7FFFFFFE).coerceAtMost(srcW - 2)
                val srcOff = sy * srcW + sx
                val dstOff = y * dstW + x
                if (srcOff + 1 < srcUv.size && dstOff + 1 < dstUv.size) {
                    dstUv[dstOff] = srcUv[srcOff]
                    dstUv[dstOff + 1] = srcUv[srcOff + 1]
                }
            }
        }
    }

    private fun ensure(buf: ByteArray, size: Int): ByteArray {
        return if (buf.size >= size) buf else ByteArray(size)
    }
}

data class Nv12Frame(
    val y: ByteArray,
    val uv: ByteArray,
    val width: Int,
    val height: Int
)
