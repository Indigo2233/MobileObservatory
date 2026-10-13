package com.indigo.mobileobservatory.stacking

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.max

/** Conversion is the LAST display step; the supplied linear/processed pixels are never changed. */
object StackBitmap {
    /** Tone-map directly into a Bitmap using one Float row and one ARGB row. Even a
     * downsampled preview uses the source-resolution filter neighbourhoods in PreparedStack. */
    fun create(image: PreparedStack, maximumSide: Int = Int.MAX_VALUE, checkCancelled: () -> Unit = {}): Bitmap {
        require(maximumSide > 0)
        val step = max(1,ceil(max(image.width,image.height).toDouble()/maximumSide).toInt())
        val width = (image.width+step-1)/step
        val height = (image.height+step-1)/step
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            val values = FloatArray(width*image.channels)
            val row = IntArray(width)
            for (y in 0 until height) {
                checkCancelled()
                image.writeRow((y*step).coerceAtMost(image.height-1),values,step)
                for (x in 0 until width) {
                    val offset = x*image.channels
                    fun byte(channel: Int): Int = (values[offset+channel].let {
                        if (it.isFinite()) it.coerceIn(0f,1f) else 0f
                    }*255f+.5f).toInt()
                    val red = byte(0)
                    val green = byte(if (image.channels == 3) 1 else 0)
                    val blue = byte(if (image.channels == 3) 2 else 0)
                    row[x] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
                }
                bitmap.setPixels(row,0,width,0,y,width,1)
            }
            image.finishRender()
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    fun create(image: LinearImage, maximumSide: Int = Int.MAX_VALUE, checkCancelled: () -> Unit = {}): Bitmap {
        require(maximumSide > 0)
        val step = max(1, ceil(max(image.width, image.height).toDouble() / maximumSide).toInt())
        val width = (image.width + step - 1) / step
        val height = (image.height + step - 1) / step
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            // One row of integer scratch, regardless of source dimensions.
            val row = IntArray(width)
            for (y in 0 until height) {
                checkCancelled()
                for (x in 0 until width) {
                    val offset = ((y * step).coerceAtMost(image.height - 1) * image.width +
                        (x * step).coerceAtMost(image.width - 1)) * image.channels
                    fun byte(channel: Int): Int = (image.pixels[offset + channel].let {
                        if (it.isFinite()) it.coerceIn(0f, 1f) else 0f
                    } * 255f + .5f).toInt()
                    val r = byte(0)
                    val g = byte(if (image.channels == 3) 1 else 0)
                    val b = byte(if (image.channels == 3) 2 else 0)
                    row[x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }
}
