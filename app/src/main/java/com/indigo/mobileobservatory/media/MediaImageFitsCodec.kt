package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil

data class DecodedMediaImage(
    val image: MediaImagePixels,
    val originalWidth: Int,
    val originalHeight: Int,
    val header: Map<String, String> = emptyMap(),
    val displayNormalized: Boolean = false
)

/** Uncompressed primary FITS images; Bayer samples are demosaiced at their original CFA positions. */
object MediaImageFitsCodec {
    fun read(file: File, maximumSide: Int = Int.MAX_VALUE, check: () -> Unit = {},
             admit: (Long) -> Unit = {}): DecodedMediaImage = RandomAccessFile(file, "r").use { input ->
        val h = FitsHeaderParser.read(input)
        val width = h.intValue("NAXIS1") ?: 0
        val height = h.intValue("NAXIS2") ?: 0
        val axes = h.intValue("NAXIS") ?: 0
        val planes = if (axes == 3) h.intValue("NAXIS3") ?: 0 else 1
        val bitpix = h.intValue("BITPIX") ?: 0
        require(h["SIMPLE"] == "T" && axes in 2..3 && width > 0 && height > 0 && planes in listOf(1, 3)) {
            "Unsupported FITS image geometry"
        }
        require(bitpix in listOf(8, 16, 32, -32) && maximumSide > 0) { "Unsupported FITS sample type" }
        val bytesPerSample = abs(bitpix) / 8
        val samples = Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), planes.toLong())
        val dataBytes = Math.multiplyExact(samples, bytesPerSample.toLong())
        require(dataBytes <= Int.MAX_VALUE && dataBytes <= input.length() - h.dataOffset) { "Truncated or oversized FITS image" }
        val scale = h.doubleValue("BSCALE") ?: 1.0
        val zero = h.doubleValue("BZERO") ?: 0.0
        require(scale.isFinite() && zero.isFinite()) { "Invalid FITS scaling" }
        val pattern = h["BAYERPAT"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        require(pattern == null || (planes == 1 && width >= 2 && height >= 2 && pattern in listOf("RGGB", "GRBG", "GBRG", "BGGR"))) {
            "Unsupported Bayer pattern"
        }
        val channels = if (pattern != null || planes == 3) 3 else 1
        val step = ceil(maxOf(width, height).toDouble() / maximumSide).toInt().coerceAtLeast(1)
        val outWidth = ((width.toLong() + step - 1) / step).toInt()
        val outHeight = ((height.toLong() + step - 1) / step).toInt()
        val outputSamples = Math.multiplyExact(outWidth.toLong() * outHeight, channels.toLong())
        require(outputSamples <= Int.MAX_VALUE) { "Image buffer exceeds the supported size" }
        admit(outputSamples * 4)
        check()
        val values = FloatArray(outputSamples.toInt())
        val data = input.channel.map(FileChannel.MapMode.READ_ONLY, h.dataOffset, dataBytes).order(ByteOrder.BIG_ENDIAN)
        fun sample(x: Int, y: Int, plane: Int = 0): Float {
            val index = (plane.toLong() * width * height + y.toLong() * width + x) * bytesPerSample
            val offset = index.toInt()
            val raw = when (bitpix) {
                8 -> (data.get(offset).toInt() and 255).toDouble()
                16 -> data.getShort(offset).toDouble()
                32 -> data.getInt(offset).toDouble()
                else -> data.getFloat(offset).toDouble()
            }
            val value = (raw * scale + zero).toFloat()
            require(value.isFinite()) { "Non-finite FITS sample" }
            return value
        }
        fun reflect(value: Int, size: Int) = when {
            value < 0 -> -value
            value >= size -> 2 * size - 2 - value
            else -> value
        }.coerceIn(0, size - 1)
        fun raw(x: Int, y: Int) = sample(reflect(x, width), reflect(y, height))
        val redX = ((if (pattern == "GRBG" || pattern == "BGGR") 1 else 0) - (h.intValue("XBAYROFF") ?: 0)) and 1
        val redY = ((if (pattern == "GBRG" || pattern == "BGGR") 1 else 0) - (h.intValue("YBAYROFF") ?: 0)) and 1
        for (y in 0 until outHeight) {
            check()
            val sourceY = y * step
            for (x in 0 until outWidth) {
                val sourceX = x * step
                val i = (y * outWidth + x) * channels
                if (pattern == null) {
                    for (c in 0 until channels) values[i + c] = sample(sourceX, sourceY, c)
                } else {
                    val center = raw(sourceX, sourceY)
                    val horizontal = (raw(sourceX - 1, sourceY) + raw(sourceX + 1, sourceY)) * .5f
                    val vertical = (raw(sourceX, sourceY - 1) + raw(sourceX, sourceY + 1)) * .5f
                    val diagonal = (raw(sourceX - 1, sourceY - 1) + raw(sourceX + 1, sourceY - 1) +
                        raw(sourceX - 1, sourceY + 1) + raw(sourceX + 1, sourceY + 1)) * .25f
                    val isRed = (sourceX and 1) == redX && (sourceY and 1) == redY
                    val isBlue = (sourceX and 1) != redX && (sourceY and 1) != redY
                    values[i] = when { isRed -> center; isBlue -> diagonal; (sourceY and 1) == redY -> horizontal; else -> vertical }
                    values[i + 1] = if (isRed || isBlue) (horizontal + vertical) * .5f else center
                    values[i + 2] = when { isBlue -> center; isRed -> diagonal; (sourceY and 1) == redY -> vertical; else -> horizontal }
                }
            }
        }
        DecodedMediaImage(MediaImagePixels(outWidth, outHeight, channels, values), width, height,
            h.cards, h["BUNIT"] == "normalized display" && h["EDITED"] == "T")
    }

    /** Float32 planar RGB is a true-color FITS cube. Mono stays a two-dimensional image. */
    fun write(file: File, image: MediaImagePixels, metadata: Map<String, String>, check: () -> Unit = {}) {
        val sourceHeader = metadata.filterKeys { it.startsWith("fits.") }.mapKeys { it.key.removePrefix("fits.") }
        val cards = mutableListOf(card("SIMPLE", "T"), card("BITPIX", "-32"),
            card("NAXIS", if (image.channels == 3) "3" else "2"), card("NAXIS1", image.width.toString()), card("NAXIS2", image.height.toString()))
        if (image.channels == 3) cards += card("NAXIS3", "3")
        cards += card("EXTEND", "T")
        cards += card("BUNIT", quote("normalized display"))
        cards += card("COLORSPC", quote(if (image.channels == 3) "RGB" else "MONO"))
        cards += card("EDITED", "T")
        cards += card("DATE", quote(Instant.now().toString()))
        cards += card("SOFTWARE", quote("Indigo Observatory"))
        // Geometry and row ordering are retained, so the source WCS remains applicable.
        for ((key, value) in sourceHeader) {
            if (key in listOf("DATE-OBS", "INSTRUME", "OBJECT", "FILTER", "CTYPE1", "CTYPE2", "CUNIT1", "CUNIT2", "RADESYS", "WCSNAME")) {
                cards += card(key, quote(value))
            } else if (key in listOf("EXPOSURE", "EXPTIME", "GAIN", "GAINDB", "ISOSPEED", "XPIXSZ", "YPIXSZ", "XBINNING", "YBINNING", "FOCALLEN", "EQUINOX", "WCSAXES", "LONPOLE", "LATPOLE", "PIXSCALE", "FOVW", "FOVH") ||
                key.matches(Regex("(CRPIX|CRVAL|CDELT|CROTA)[12]|(CD|PC)[12]_[12]|PV[12]_[0-9]+|(A|B|AP|BP)_ORDER|(A|B|AP|BP)_[0-9]+_[0-9]+"))) {
                if (value.replace('D', 'E', ignoreCase = true).toDoubleOrNull()?.isFinite() == true) cards += card(key, value.replace('d', 'D'))
            }
        }
        cards += "HISTORY  Image-library edits are baked into the floating-point display values.".padEnd(80).take(80)
        val json = metadataJson(metadata).toByteArray(Charsets.UTF_8)
        require(json.size <= 16 * 1024 * 1024)
        FileOutputStream(file).use { fileOut ->
            val out = DataOutputStream(BufferedOutputStream(fileOut, 64 * 1024))
            writeHeader(out, cards)
            for (channel in 0 until image.channels) for (y in 0 until image.height) {
                check()
                for (x in 0 until image.width) {
                    val value = image.pixels[(y * image.width + x) * image.channels + channel]
                    require(value.isFinite()) { "Non-finite FITS output sample" }
                    out.writeFloat(value)
                }
            }
            pad(out, image.pixels.size.toLong() * 4)
            writeHeader(out, listOf(card("XTENSION", quote("IMAGE   ")), card("BITPIX", "8"), card("NAXIS", "1"),
                card("NAXIS1", json.size.toString()), card("PCOUNT", "0"), card("GCOUNT", "1"),
                card("EXTNAME", quote("EDITMETA")), card("ENCODING", quote("UTF-8 JSON"))))
            out.write(json); pad(out, json.size.toLong()); out.flush(); fileOut.fd.sync()
        }
    }

    fun metadataJson(values: Map<String, String>): String = values.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
        "${jsonString(it.key)}:${jsonString(it.value)}"
    }
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
    private fun quote(value: String): String = buildString {
        append('\'')
        for (c in value) {
            val escaped = if (c == '\'') "''" else if (c.code in 32..126) c.toString() else "?"
            if (length + escaped.length > 67) break
            append(escaped)
        }
        append('\'')
    }
    private fun card(key: String, value: String) =
        (key.padEnd(8).take(8) + "= " + if (value.startsWith("'")) value else value.padStart(20)).padEnd(80).take(80)
    private fun writeHeader(out: DataOutputStream, cards: List<String>) {
        val text = (cards + "END".padEnd(80)).joinToString("")
        out.write(text.padEnd(((text.length + 2879) / 2880) * 2880).toByteArray(Charsets.US_ASCII))
    }
    private fun pad(out: DataOutputStream, bytes: Long) {
        val count = ((2880 - bytes % 2880) % 2880).toInt()
        if (count > 0) out.write(ByteArray(count))
    }
}
