package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat

/** PHD2 QuickLRecon's unsigned, sliding 2x2 mean. The image dimensions remain unchanged.
 * A bounded row cache avoids another full sensor-sized image allocation on Android.
 * Craig Stark (2006–2010), BSD; distributed in assets/THIRD_PARTY_PHD2.txt. */
internal class GuideImage(private val frame: FrameData) {
    val width = frame.width
    val height = frame.height
    val bayer = frame.pixelFormat.isBayer
    val bits = frame.pixelFormat.nativeBits
    val sensorOffset = if (bayer) .5 else 0.0
    private val rows = arrayOfNulls<ShortArray>(if (bayer) minOf(height, 32) else 0)
    private val keys = IntArray(rows.size) { -1 }
    internal val cachedPixels get() = rows.sumOf { it?.size ?: 0 }

    fun value(x: Int, y: Int): Int {
        if (!bayer) return GuidePixelAccess.raw(frame, x, y)
        val slot = y % rows.size
        val row = rows[slot] ?: ShortArray(width).also { rows[slot] = it }
        if (keys[slot] != y) {
            if (y < height - 1) {
                var left = GuidePixelAccess.raw(frame, 0, y) + GuidePixelAccess.raw(frame, 0, y + 1)
                for (column in 0 until width - 1) {
                    val right = GuidePixelAccess.raw(frame, column + 1, y) + GuidePixelAccess.raw(frame, column + 1, y + 1)
                    row[column] = ((left + right) ushr 2).toShort()
                    left = right
                }
                row[width - 1] = (left ushr 1).toShort()
            } else {
                var left = GuidePixelAccess.raw(frame, 0, y)
                for (column in 0 until width - 1) {
                    val right = GuidePixelAccess.raw(frame, column + 1, y)
                    row[column] = ((left + right) ushr 1).toShort()
                    left = right
                }
                row[width - 1] = left.toShort()
            }
            keys[slot] = y
        }
        return row[x].toInt() and 65535
    }
}

/** Raw samples stay available for clipping checks and unsmoothed PSF diagnostics. */
internal object GuidePixelAccess {
    fun raw(frame: FrameData, x: Int, y: Int): Int {
        val index = y * frame.width + x
        return when (frame.pixelFormat) {
            PixelFormat.RGB24 -> {
                val base = index * 3
                ((frame.data[base].toInt() and 255) * 299 + (frame.data[base + 1].toInt() and 255) * 587 +
                    (frame.data[base + 2].toInt() and 255) * 114) / 1000
            }
            PixelFormat.RGB48 -> {
                val base = index * 6
                if (base + 5 >= frame.data.size) 0 else
                    (u16(frame.data, base) * 299 + u16(frame.data, base + 2) * 587 + u16(frame.data, base + 4) * 114) / 1000
            }
            else -> if (frame.pixelFormat.bytesPerPixel == 1) {
                if (index >= frame.data.size) 0 else frame.data[index].toInt() and 255
            } else {
                val base = index * 2
                if (base + 1 >= frame.data.size) 0 else u16(frame.data, base)
            }
        }
    }

    fun peak(frame: FrameData, x: Int, y: Int): Int {
        val index = (y * frame.width + x) * frame.pixelFormat.bytesPerPixel
        return when (frame.pixelFormat) {
            PixelFormat.RGB24 -> maxOf(frame.data[index].toInt() and 255, frame.data[index + 1].toInt() and 255, frame.data[index + 2].toInt() and 255)
            PixelFormat.RGB48 -> maxOf(u16(frame.data, index), u16(frame.data, index + 2), u16(frame.data, index + 4))
            else -> raw(frame, x, y)
        }
    }
    private fun u16(data: ByteArray, index: Int) = (data[index].toInt() and 255) or ((data[index + 1].toInt() and 255) shl 8)
}
