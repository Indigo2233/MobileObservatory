package com.indigo.mobileobservatory.util

import android.graphics.Bitmap
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat

object ImageUtils {

    fun mono8ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val v = data[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun mono10ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val lo = data[i * 2].toInt() and 0xFF
            val hi = data[i * 2 + 1].toInt() and 0xFF
            val raw10 = (hi shl 8) or lo
            val v = (raw10 shr 2).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun formatExposure(us: Float): String {
        return when {
            us >= 1_000_000f -> "%.2fs".format(us / 1_000_000f)
            us >= 1000f -> "%.1fms".format(us / 1000f)
            else -> "%.0fμs".format(us)
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val h = m / 60
        return "%02d:%02d:%02d".format(h, m % 60, s % 60)
    }
}
