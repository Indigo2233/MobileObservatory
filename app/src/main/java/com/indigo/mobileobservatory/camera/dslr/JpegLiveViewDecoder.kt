package com.indigo.mobileobservatory.camera.dslr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat

object JpegLiveViewDecoder {
    fun decode(jpeg: ByteArray, frameId: Long): FrameData? {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            val rgb = ByteArray(width * height * 3)
            for (i in argb.indices) {
                val pixel = argb[i]
                val base = i * 3
                rgb[base] = ((pixel shr 16) and 0xFF).toByte()
                rgb[base + 1] = ((pixel shr 8) and 0xFF).toByte()
                rgb[base + 2] = (pixel and 0xFF).toByte()
            }
            FrameData(
                data = rgb,
                width = width,
                height = height,
                pixelFormat = PixelFormat.RGB24,
                frameId = frameId,
                timestamp = System.currentTimeMillis()
            )
        } finally {
            if (bitmap.config != Bitmap.Config.HARDWARE) bitmap.recycle()
        }
    }
}
