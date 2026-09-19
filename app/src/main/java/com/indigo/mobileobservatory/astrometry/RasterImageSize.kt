package com.indigo.mobileobservatory.astrometry

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * JPEG/PNG dimensions without Android BitmapFactory, so plate-solve FOV
 * can be recomputed for files that have no FITS header.
 */
object RasterImageSize {
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun read(file: File): Pair<Int, Int>? {
        if (!file.isFile || file.length() < 10L) return null
        file.inputStream().use { input ->
            val header = ByteArray(8)
            if (input.read(header) < 8) return null
            if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) {
                return readJpegSize(file)
            }
            if (header.contentEquals(PNG_SIGNATURE)) {
                return readPngSize(file)
            }
        }
        return null
    }

    fun isFitsMagic(file: File): Boolean {
        if (!file.isFile) return false
        file.inputStream().use { input ->
            val buf = ByteArray(6)
            if (input.read(buf) < 6) return false
            return String(buf, StandardCharsets.US_ASCII) == "SIMPLE"
        }
    }

    private fun readPngSize(file: File): Pair<Int, Int>? {
        file.inputStream().use { input ->
            val buf = ByteArray(24)
            if (input.read(buf) < 24) return null
            if (!buf.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
            if (String(buf, 12, 4, StandardCharsets.US_ASCII) != "IHDR") return null
            val width = ByteBuffer.wrap(buf, 16, 4).order(ByteOrder.BIG_ENDIAN).int
            val height = ByteBuffer.wrap(buf, 20, 4).order(ByteOrder.BIG_ENDIAN).int
            if (width <= 0 || height <= 0) return null
            return width to height
        }
    }

    private fun readJpegSize(file: File): Pair<Int, Int>? {
        file.inputStream().buffered().use { input ->
            if (input.read() != 0xFF || input.read() != 0xD8) return null
            while (true) {
                var marker = input.read()
                while (marker == 0xFF) marker = input.read()
                if (marker < 0) return null
                if (marker == 0xD9 || marker == 0xDA) return null
                if (marker in 0xD0..0xD7) continue
                val length = readU16(input) ?: return null
                if (length < 2) return null
                val payload = length - 2
                if (marker in 0xC0..0xC3 || marker in 0xC5..0xC7 ||
                    marker in 0xC9..0xCB || marker in 0xCD..0xCF
                ) {
                    if (payload < 5) return null
                    input.read()
                    val height = readU16(input) ?: return null
                    val width = readU16(input) ?: return null
                    if (width <= 0 || height <= 0) return null
                    return width to height
                }
                if (!skip(input, payload.toLong())) return null
            }
        }
    }

    private fun readU16(input: InputStream): Int? {
        val hi = input.read()
        val lo = input.read()
        if (hi < 0 || lo < 0) return null
        return (hi shl 8) or lo
    }

    private fun skip(input: InputStream, count: Long): Boolean {
        val buffer = ByteArray(4096)
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n <= 0) return false
            remaining -= n
        }
        return true
    }
}
