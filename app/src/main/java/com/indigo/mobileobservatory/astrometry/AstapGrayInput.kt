package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.util.FitsHeader
import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** Direct bilinear CFA-to-luminance conversion for ASTAP. Only three source rows are retained. */
internal class AstapGrayInput private constructor(
    val source: File,
    val header: FitsHeader,
    val width: Int,
    val height: Int,
    val bayerPattern: String,
    private val bytesPerPixel: Int,
    private val bZero: Double
) {
    companion object {
        private val patterns = setOf("RGGB", "GRBG", "GBRG", "BGGR")

        fun inspect(source: File): AstapGrayInput? {
            if (source.extension.lowercase() !in setOf("fit", "fits")) return null
            val header = runCatching { FitsHeaderParser.read(source) }.getOrNull() ?: return null
            if (header.intValue("NAXIS") != 2) return null
            val pattern = header["BAYERPAT"]?.trim()?.uppercase()?.takeIf { it in patterns } ?: return null
            val bitpix = header.intValue("BITPIX") ?: return null
            if (bitpix !in listOf(8, 16)) return null
            if ((header.doubleValue("BSCALE") ?: 1.0) != 1.0) return null
            val width = header.intValue("NAXIS1") ?: return null
            val height = header.intValue("NAXIS2") ?: return null
            if (width < 2 || height < 2) return null
            val bytesPerPixel = bitpix / 8
            if (source.length() < header.dataOffset + width.toLong() * height * bytesPerPixel) return null
            return AstapGrayInput(source, header, width, height, pattern, bytesPerPixel,
                header.doubleValue("BZERO") ?: 0.0)
        }
    }

    fun write(target: File, check: () -> Unit = {}) {
        require(source.canonicalFile != target.canonicalFile && !target.exists()) {
            "Independent solver input required"
        }
        check()
        require(target.createNewFile()) { "Solver input already exists" }
        try {
            BufferedOutputStream(FileOutputStream(target), 65536).use { output ->
                writeHeader(output)
                writePixels(output, check)
            }
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    private fun writeHeader(output: OutputStream) {
        val numericKeys = setOf(
            "EXPOSURE", "EXPTIME", "GAIN", "GAINDB", "XPIXSZ", "YPIXSZ",
            "FOCALLEN", "FOVH", "FOVW", "PIXSCALE", "RA", "DEC"
        )
        val stringKeys = setOf(
            "DATE-OBS", "DATE_OBS", "INSTRUME", "SOFTWARE", "OBJECT",
            "FILTER", "TELESCOP", "OBJCTRA", "OBJCTDEC", "IMAGETYP"
        )
        val inputBits = header.intValue("VALIDBIT")?.takeIf { it in 1..16 }
            ?: (if (bytesPerPixel == 1) 8 else 16)
        val cards = mutableListOf(
            fitsCard("SIMPLE", "T"),
            fitsCard("BITPIX", "16"),
            fitsCard("NAXIS", "2"),
            fitsCard("NAXIS1", width.toString()),
            fitsCard("NAXIS2", height.toString()),
            fitsCard("BSCALE", "1"),
            fitsCard("BZERO", "32768"),
            fitsCard("VALIDBIT", if (bytesPerPixel == 1) "16" else inputBits.toString())
        )
        header.cards.forEach { (key, value) ->
            if (key in numericKeys && header.doubleValue(key)?.isFinite() == true) {
                cards += fitsCard(key, value)
            }
            if (key in stringKeys) cards += fitsCard(key, "'${value.replace("'", "''")}'")
        }
        for (key in listOf("XBINNING", "YBINNING")) {
            header.intValue(key)?.let { cards += fitsCard(key, it.toString()) }
        }
        cards += fitsCard("DEBAYER", "'BILINEAR'")
        val text = (cards + "END".padEnd(80)).joinToString("")
        output.write(text.padEnd(((text.length + 2879) / 2880) * 2880).toByteArray(Charsets.US_ASCII))
    }

    private fun writePixels(output: OutputStream, check: () -> Unit) {
        val paddedWidth = width + 2
        var previous = IntArray(paddedWidth)
        var current = IntArray(paddedWidth)
        var next = IntArray(paddedWidth)
        val sourceRow = ByteArray(Math.multiplyExact(width, bytesPerPixel))
        val outputRow = ByteArray(Math.multiplyExact(width, 2))
        RandomAccessFile(source, "r").use { input ->
            input.seek(header.dataOffset)
            readRow(input, current, sourceRow)
            if (height > 1) {
                readRow(input, next, sourceRow)
                current.copyInto(previous)
            } else {
                current.copyInto(next)
                current.copyInto(previous)
            }
            repeat(height) { y ->
                check()
                writeGrayRow(outputRow, previous, current, next, y)
                output.write(outputRow)
                if (y < height - 1) {
                    val oldPrevious = previous
                    previous = current
                    current = next
                    next = oldPrevious
                    if (y + 2 < height) readRow(input, next, sourceRow) else current.copyInto(next)
                }
            }
            val bytes = width.toLong() * height * 2
            output.write(ByteArray(((2880 - bytes % 2880) % 2880).toInt()))
        }
    }

    private fun readRow(input: RandomAccessFile, target: IntArray, bytes: ByteArray) {
        input.readFully(bytes)
        if (bytesPerPixel == 1) {
            for (x in 0 until width) {
                target[x + 1] = ((bytes[x].toInt() and 255) + bZero).toInt().coerceIn(0, 65535)
            }
        } else {
            for (x in 0 until width) {
                val physical = ((bytes[x * 2].toInt() and 255) shl 8) or
                    (bytes[x * 2 + 1].toInt() and 255)
                val signed = physical.toShort().toInt()
                target[x + 1] = (signed + bZero).toInt().coerceIn(0, 65535)
            }
        }
        target[0] = target[2]
        target[width + 1] = target[width - 1]
    }

    private fun writeGrayRow(output: ByteArray, previous: IntArray, current: IntArray, next: IntArray, y: Int) {
        val redX = if (bayerPattern == "RGGB" || bayerPattern == "GBRG") 0 else 1
        val redY = if (bayerPattern == "RGGB" || bayerPattern == "GRBG") 0 else 1
        for (x in 0 until width) {
            val p = x + 1
            val raw = current[p]
            val red: Int
            val green: Int
            val blue: Int
            val isRed = (x and 1) == redX && (y and 1) == redY
            val isBlue = (x and 1) != redX && (y and 1) != redY
            when {
                isRed -> {
                    red = raw
                    green = cross(previous, current, next, p)
                    blue = diagonal(previous, next, p)
                }
                isBlue -> {
                    red = diagonal(previous, next, p)
                    green = cross(previous, current, next, p)
                    blue = raw
                }
                else -> {
                    green = raw
                    if ((x and 1) != redX) {
                        red = (current[p - 1] + current[p + 1] + 1) / 2
                        blue = (previous[p] + next[p] + 1) / 2
                    } else {
                        red = (previous[p] + next[p] + 1) / 2
                        blue = (current[p - 1] + current[p + 1] + 1) / 2
                    }
                }
            }
            val luminance = (((77 * red + 150 * green + 29 * blue + 128) shr 8) *
                if (bytesPerPixel == 1) 257 else 1).coerceIn(0, 65535)
            val signed = luminance - 32768
            output[x * 2] = (signed ushr 8).toByte()
            output[x * 2 + 1] = signed.toByte()
        }
    }

    private fun cross(previous: IntArray, current: IntArray, next: IntArray, p: Int): Int =
        (current[p - 1] + current[p + 1] + previous[p] + next[p] + 2) / 4

    private fun diagonal(previous: IntArray, next: IntArray, p: Int): Int =
        (previous[p - 1] + previous[p + 1] + next[p - 1] + next[p + 1] + 2) / 4

    private fun fitsCard(key: String, value: String): String =
        (key.padEnd(8) + "= " + if (value.startsWith("'")) value else value.padStart(20)).padEnd(80).take(80)
}
