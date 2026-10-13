package com.indigo.mobileobservatory.stacking

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** FITS primary image: IEEE 32-bit big-endian floats, axis 3 = R,G,B planes.
 * Normalized linear samples retain negatives/above-white values and are never display stretched.
 * Metadata is also written losslessly as UTF-8 JSON in an IMAGE extension named STACKMETA.
 * The extension avoids silently truncating source identifiers or processing parameters in cards.
 */
object StackFitsWriter {
    fun write(file: File, image: LinearImage, metadata: Map<String, String> = emptyMap(),
              cancelCheck: () -> Unit = {}) {
        require(!file.exists()) { "Result already exists; choose an independent output name" }
        val parent = checkNotNull(file.absoluteFile.parentFile) { "Output has no parent directory" }
        require(parent.isDirectory || parent.mkdirs()) { "Cannot create output directory" }
        val json = metadata.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
            "${jsonString(it.key)}:${jsonString(it.value)}"
        }.toByteArray(Charsets.UTF_8)
        require(json.size <= 16 * 1024 * 1024) { "Result metadata is too large" }
        val required = image.pixels.size.toLong() * 4 + json.size + 4 * BLOCK
        if (parent.usableSpace in 1 until required) throw IOException("Not enough storage for FITS; free at least $required bytes")
        val temporary = File(parent, ".${file.name}.${UUID.randomUUID()}.partial")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporary), 65536)).use { out ->
                val cards = mutableListOf(
                    card("SIMPLE", "T"), card("BITPIX", "-32"),
                    card("NAXIS", if (image.channels == 3) "3" else "2"),
                    card("NAXIS1", image.width.toString()), card("NAXIS2", image.height.toString())
                )
                if (image.channels == 3) cards += card("NAXIS3", "3")
                cards += card("EXTEND", "T")
                cards += card("BUNIT", quoted("normalized linear"))
                cards += card("DATE", quoted(Instant.now().toString()))
                cards += card("SOFTWARE", quoted("Indigo Observatory"))
                cards += card("COLORSPC", quoted(if (image.channels == 3) "RGB" else "MONO"))
                // Useful standard cards, with full values retained in STACKMETA regardless of length.
                metadata["frameCount"]?.toLongOrNull()?.let { cards += card("NCOMBINE", it.toString()) }
                metadata["exposureSeconds"]?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
                    cards += card("EXPTIME", it.toString())
                }
                metadata["calibrated"]?.let { cards += card("CALIBRAT", if (it == "true") "T" else "F") }
                metadata["source"]?.let { cards += card("OBJECT", quoted(it.take(60))) }
                writeHeader(out, cards)
                for (c in 0 until image.channels) for (y in 0 until image.height) {
                    cancelCheck()
                    for (x in 0 until image.width) {
                        val value = image.pixels[(y * image.width + x) * image.channels + c]
                        require(value.isFinite()) { "Non-finite pixel in stack export" }
                        out.writeFloat(value)
                    }
                }
                pad(out, image.pixels.size.toLong() * 4)
                writeHeader(out, listOf(card("XTENSION", quoted("IMAGE   ")), card("BITPIX", "8"),
                    card("NAXIS", "1"), card("NAXIS1", json.size.toString()), card("PCOUNT", "0"),
                    card("GCOUNT", "1"), card("EXTNAME", quoted("STACKMETA")), card("ENCODING", quoted("UTF-8 JSON"))))
                out.write(json)
                pad(out, json.size.toLong())
            }
            cancelCheck()
            if (file.exists() || !temporary.renameTo(file)) throw IOException("Could not finalize FITS output")
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    private const val BLOCK = 2880
    private fun quoted(value: String): String = buildString {
        append('\'')
        for (c in value) {
            val escaped = if (c == '\'') "''" else if (c.code in 32..126) c.toString() else "?"
            if (length + escaped.length > 67) break
            append(escaped)
        }
        append('\'')
    }
    private fun card(key: String, value: String): String =
        (key.padEnd(8) + "= " + if (value.startsWith("'")) value else value.padStart(20)).padEnd(80).take(80)
    private fun writeHeader(out: DataOutputStream, cards: List<String>) {
        val text = (cards + "END".padEnd(80)).joinToString("")
        out.write(text.padEnd(((text.length + BLOCK - 1) / BLOCK) * BLOCK).toByteArray(Charsets.US_ASCII))
    }
    private fun pad(out: DataOutputStream, count: Long) {
        val padding = ((BLOCK - count % BLOCK) % BLOCK).toInt()
        if (padding > 0) out.write(ByteArray(padding))
    }
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
}
