package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/** Bounded reader for uncompressed floating FITS primary images. Existing integer/Bayer playback
 * remains in the camera/player pipeline. RGB planes are converted to our interleaved model.
 */
object StackFitsReader {
    fun isFloatingPoint(file: File): Boolean = RandomAccessFile(file, "r").use {
        FitsHeaderParser.read(it)["BITPIX"] == "-32"
    }

    /** Only our normalized, already-demosaiced products use the stack display recipe. Ordinary
     * floating FITS may contain ADU counts or a CFA mosaic and need the legacy display path. */
    fun isNormalizedStack(file: File): Boolean {
        val h = RandomAccessFile(file, "r").use { FitsHeaderParser.read(it) }
        if (h["BITPIX"] != "-32" || "BAYERPAT" in h) return false
        if (h["BUNIT"]?.trim() == "normalized linear") return true
        return readMetadata(file)["schema"] == "indigo-stack-1"
    }

    /** Reads the optional lossless recipe extension without loading image pixels. */
    fun readMetadata(file: File): Map<String, String> = RandomAccessFile(file, "r").use { reader ->
        val h = FitsHeaderParser.read(reader)
        val bytesPerSample = when (h["BITPIX"]) { "8" -> 1L; "16" -> 2L; "32", "-32" -> 4L; else -> return@use emptyMap() }
        val axes = h["NAXIS"]?.toIntOrNull() ?: return@use emptyMap()
        require(axes in 2..3)
        var samples = 1L
        for (axis in 1..axes) {
            val dimension = h["NAXIS$axis"]?.toLongOrNull() ?: error("Invalid FITS dimension")
            require(dimension > 0)
            samples = Math.multiplyExact(samples, dimension)
        }
        val primaryBytes = Math.multiplyExact(samples, bytesPerSample)
        val primaryEnd = Math.addExact(reader.filePointer, primaryBytes)
        val extensionOffset = Math.multiplyExact(Math.addExact(primaryEnd, 2879L) / 2880L, 2880L)
        if (extensionOffset >= reader.length()) return@use emptyMap()
        reader.seek(extensionOffset)
        val extension = FitsHeaderParser.read(reader)
        if (extension["EXTNAME"] !in listOf("STACKMETA", "EDITMETA")) return@use emptyMap()
        require(extension["BITPIX"] == "8" && extension["NAXIS"] == "1") { "Invalid stack metadata extension" }
        val size = extension["NAXIS1"]?.toIntOrNull() ?: error("Missing metadata length")
        require(size in 0..16 * 1024 * 1024 && reader.length() - reader.filePointer >= size) { "Invalid metadata length" }
        val bytes = ByteArray(size); reader.readFully(bytes)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        json.keys().asSequence().associateWith { json.getString(it) }
    }

    fun read(file: File, maxPixels: Long = LinearImage.MAX_PIXELS): LinearImage =
        RandomAccessFile(file, "r").use { reader ->
            val h = FitsHeaderParser.read(reader)
            require(h["SIMPLE"] == "T" && h["BITPIX"] == "-32") { "Not a 32-bit floating FITS image" }
            val axes = h["NAXIS"]?.toIntOrNull()
            require(axes == 2 || axes == 3) { "Unsupported FITS axes" }
            val w = h["NAXIS1"]?.toIntOrNull() ?: 0
            val height = h["NAXIS2"]?.toIntOrNull() ?: 0
            val channels = if (axes == 3) h["NAXIS3"]?.toIntOrNull() ?: 0 else 1
            require(w > 0 && height > 0 && w.toLong() * height <= maxPixels && w.toLong() * height <= LinearImage.MAX_PIXELS) {
                "FITS image dimensions exceed memory budget"
            }
            require(channels == 1 || channels == 3) { "Only mono and RGB FITS are supported" }
            val samples = w.toLong() * height * channels
            require(reader.length() - reader.filePointer >= samples * 4L) { "Truncated FITS primary image" }
            val scale = h["BSCALE"]?.toDoubleOrNull() ?: 1.0
            val zero = h["BZERO"]?.toDoubleOrNull() ?: 0.0
            require(scale.isFinite() && zero.isFinite()) { "Invalid FITS scaling" }
            val pixels = FloatArray(samples.toInt())
            val row = ByteArray(w * 4)
            val buffer = ByteBuffer.wrap(row).order(ByteOrder.BIG_ENDIAN)
            for (c in 0 until channels) for (y in 0 until height) {
                reader.readFully(row)
                buffer.rewind()
                for (x in 0 until w) {
                    val v = (buffer.float * scale + zero).toFloat()
                    require(v.isFinite()) { "Non-finite FITS sample" }
                    pixels[(y * w + x) * channels + c] = v
                }
            }
            LinearImage(w, height, channels, pixels)
        }

}
