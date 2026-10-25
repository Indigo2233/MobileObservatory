package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.media.MediaImagePixels
import com.indigo.mobileobservatory.media.MediaPng16Codec
import java.io.File
import kotlin.math.roundToInt

/** Preserves 16-bit display channels, with only one scalar row and one UInt16 band in memory. */
object StackPngExport {
    fun write(file: File, image: PreparedStack, check: () -> Unit = {}) {
        val row = FloatArray(Math.multiplyExact(image.width, image.channels))
        MediaPng16Codec.write(file, image.width, image.height, image.channels, check) { region ->
            val pixels = ShortArray(Math.multiplyExact(region.width * region.height, image.channels))
            for (y in 0 until region.height) {
                check(); image.writeRow(region.top + y, row)
                for (i in row.indices) {
                    require(row[i].isFinite()) { "Invalid processed sample" }
                    pixels[y * row.size + i] = (row[i].coerceIn(0f, 1f).toDouble() * 65535).roundToInt().toShort()
                }
            }
            MediaImagePixels(region.width, region.height, image.channels, pixels)
        }
    }
}
