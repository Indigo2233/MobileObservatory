package com.indigo.mobileobservatory.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

data class PreparedMediaImage(
    val source: File,
    val sourceFingerprint: String,
    val originalWidth: Int,
    val originalHeight: Int,
    val preview: MediaImagePixels,
    val window: MediaImageWindow,
    val lpGains: FloatArray,
    val sourceHeader: Map<String, String>
)

/** Preview is bounded; saving decodes the original resolution and retains floating-point FITS samples. */
object MediaImageEditing {
    fun prepare(source: File, cancelled: () -> Boolean = { false }): PreparedMediaImage {
        val key = fingerprint(source)
        val decoded = readSource(source, 1600, cancelled)
        val window = if (decoded.displayNormalized) MediaImageWindow(0f, 1f) else MediaImageProcessing.window(decoded.image)
        MediaImageProcessing.normalize(decoded.image, window) { checkCancelled(cancelled) }
        require(key == fingerprint(source)) { "source_changed" }
        return PreparedMediaImage(source, key, decoded.originalWidth, decoded.originalHeight,
            decoded.image, window, MediaImageProcessing.lp700Gains(decoded.image), decoded.header)
    }

    fun isEditedFits(file: File): Boolean {
        val header = FitsHeaderParser.read(file)
        return header["EDITED"] == "T" && header["BUNIT"] == "normalized display"
    }

    fun renderPreview(prepared: PreparedMediaImage, settings: MediaImageEditSettings,
                      cancelled: () -> Boolean = { false }): Bitmap {
        checkCancelled(cancelled)
        val result = prepared.preview.deepCopy()
        MediaImageProcessing.apply(result, settings, prepared.lpGains) { checkCancelled(cancelled) }
        return bitmap(result) { checkCancelled(cancelled) }
    }

    fun estimateWhiteBalance(prepared: PreparedMediaImage, settings: MediaImageEditSettings): FloatArray =
        MediaImageProcessing.whiteBalance(prepared.preview, settings.lp700, prepared.lpGains)

    fun export(prepared: PreparedMediaImage, capturesDir: File, format: MediaImageExportFormat,
               settings: MediaImageEditSettings, cancelled: () -> Boolean = { false }): File {
        require(prepared.sourceFingerprint == fingerprint(prepared.source)) { "source_changed" }
        val pixels = prepared.originalWidth.toLong() * prepared.originalHeight
        val floats = Math.multiplyExact(pixels, prepared.preview.channels * 4L)
        val rasterSource = prepared.source.extension.lowercase(Locale.ROOT) !in listOf("fit", "fits")
        // Raster source and encoded-result Bitmaps exist at separate stages.
        checkMemory(floats + if (rasterSource || format != MediaImageExportFormat.FIT) pixels * 4L else 0L)
        val decoded = readSource(prepared.source, Int.MAX_VALUE, cancelled)
        require(decoded.originalWidth == prepared.originalWidth && decoded.originalHeight == prepared.originalHeight &&
            decoded.image.width == prepared.originalWidth && decoded.image.height == prepared.originalHeight &&
            decoded.image.channels == prepared.preview.channels) { "source_changed" }
        MediaImageProcessing.normalize(decoded.image, prepared.window) { checkCancelled(cancelled) }
        MediaImageProcessing.apply(decoded.image, settings, prepared.lpGains) { checkCancelled(cancelled) }
        require(prepared.sourceFingerprint == fingerprint(prepared.source)) { "source_changed" }
        val directory = File(capturesDir, if (format == MediaImageExportFormat.FIT) "FITS" else "JPG")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create the image library folder")
        val required = if (format == MediaImageExportFormat.FIT) floats + 32_768 else pixels * 4 + 32_768
        if (directory.usableSpace in 1 until required + 8 * 1024 * 1024) throw IOException("storage_budget")
        val destination = nextOutputFile(prepared.source, directory, format.extension)
        val sidecar = File(directory, "${destination.nameWithoutExtension}.json")
        val temporary = File(directory, ".${destination.name}.${UUID.randomUUID()}.partial")
        val temporaryMetadata = File(directory, ".${sidecar.name}.${UUID.randomUUID()}.partial")
        val metadata = metadata(prepared, settings)
        var encoded: Bitmap? = null
        var metadataPublished = false
        var committed = false
        try {
            checkCancelled(cancelled)
            if (format == MediaImageExportFormat.FIT) {
                MediaImageFitsCodec.write(temporary, decoded.image, metadata) { checkCancelled(cancelled) }
            } else {
                encoded = bitmap(decoded.image) { checkCancelled(cancelled) }
                FileOutputStream(temporary).use { stream ->
                    val compression = if (format == MediaImageExportFormat.JPG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                    if (!encoded.compress(compression, if (format == MediaImageExportFormat.JPG) 95 else 100, stream)) {
                        throw IOException("Image encoding failed")
                    }
                    stream.fd.sync()
                }
            }
            FileOutputStream(temporaryMetadata).use { stream ->
                stream.write(MediaImageFitsCodec.metadataJson(metadata).toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            checkCancelled(cancelled)
            require(prepared.sourceFingerprint == fingerprint(prepared.source)) { "source_changed" }
            if (sidecar.exists() || !temporaryMetadata.renameTo(sidecar)) throw IOException("Could not save image metadata")
            metadataPublished = true
            if (destination.exists() || !temporary.renameTo(destination)) throw IOException("Could not finish the exported image")
            committed = true
            return destination
        } finally {
            encoded?.recycle()
            temporary.delete(); temporaryMetadata.delete()
            if (metadataPublished && !committed) sidecar.delete()
        }
    }

    internal fun readSource(source: File, maximumSide: Int, cancelled: () -> Boolean): DecodedMediaImage =
        if (source.extension.lowercase(Locale.ROOT) in listOf("fit", "fits")) {
            MediaImageFitsCodec.read(source, maximumSide, { checkCancelled(cancelled) }, ::checkMemory)
        } else readRaster(source, maximumSide, cancelled)

    private fun readRaster(source: File, maximumSide: Int, cancelled: () -> Boolean): DecodedMediaImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in listOf("image/jpeg", "image/png")) {
            "Unsupported raster image"
        }
        val orientation = runCatching { ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val swap = orientation in 5..8
        var sample = 1
        while ((maxOf(bounds.outWidth, bounds.outHeight).toLong() + sample - 1) / sample > maximumSide) sample *= 2
        val decodedWidth = (bounds.outWidth.toLong() + sample - 1) / sample
        val decodedHeight = (bounds.outHeight.toLong() + sample - 1) / sample
        checkMemory(decodedWidth * decodedHeight * 16)
        checkCancelled(cancelled)
        val input = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }) ?: throw IOException("Could not decode the source image")
        try {
            val width = if (swap) input.height else input.width
            val height = if (swap) input.width else input.height
            val count = width.toLong() * height * 3
            require(count <= Int.MAX_VALUE) { "memory_budget" }
            val values = FloatArray(count.toInt())
            val row = IntArray(input.width)
            for (y in 0 until input.height) {
                checkCancelled(cancelled)
                input.getPixels(row, 0, input.width, 0, y, input.width, 1)
                for (x in row.indices) {
                    val dx = when (orientation) { 2, 3 -> input.width - 1 - x; 5, 8 -> y; 6, 7 -> input.height - 1 - y; else -> x }
                    val dy = when (orientation) { 3, 4 -> input.height - 1 - y; 5, 6 -> x; 7, 8 -> input.width - 1 - x; else -> y }
                    val i = (dy * width + dx) * 3
                    val pixel = row[x]
                    val alpha = ((pixel ushr 24) and 255) / 255f
                    values[i] = ((pixel ushr 16) and 255) / 255f * alpha
                    values[i + 1] = ((pixel ushr 8) and 255) / 255f * alpha
                    values[i + 2] = (pixel and 255) / 255f * alpha
                }
            }
            return DecodedMediaImage(MediaImagePixels(width, height, 3, values),
                if (swap) bounds.outHeight else bounds.outWidth, if (swap) bounds.outWidth else bounds.outHeight,
                displayNormalized = true)
        } finally { input.recycle() }
    }

    private fun bitmap(image: MediaImagePixels, check: () -> Unit): Bitmap {
        check()
        val output = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(image.width)
            for (y in 0 until image.height) {
                check()
                for (x in 0 until image.width) {
                    val i = (y * image.width + x) * image.channels
                    fun byte(channel: Int) = (image.pixels[i + channel].coerceIn(0f, 1f) * 255f).roundToInt()
                    row[x] = (0xff shl 24) or (byte(0) shl 16) or
                        (byte(if (image.channels == 3) 1 else 0) shl 8) or byte(if (image.channels == 3) 2 else 0)
                }
                output.setPixels(row, 0, image.width, 0, y, image.width, 1)
            }
            return output
        } catch (failure: Throwable) { output.recycle(); throw failure }
    }

    private fun checkMemory(bytes: Long) {
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val budget = minOf((runtime.maxMemory() * .60).toLong(), (available * .80).toLong())
        require(bytes > 0 && bytes <= budget) { "memory_budget" }
    }
    private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw CancellationException("Image edit cancelled") }
    private fun fingerprint(file: File): String {
        require(file.isFile && file.length() > 0) { "source_changed" }
        return "${file.absolutePath}:${file.length()}:${file.lastModified()}"
    }
    private fun nextOutputFile(source: File, directory: File, extension: String): File {
        val stem = source.nameWithoutExtension.map { if (it.isLetterOrDigit() || it in "-_") it else '_' }
            .joinToString("").trim('_', '-').take(56).ifBlank { "image" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
        return File(directory, "$stem-edited-$stamp-${UUID.randomUUID().toString().take(8)}.$extension")
    }
    private fun metadata(prepared: PreparedMediaImage, settings: MediaImageEditSettings): Map<String, String> =
        prepared.sourceHeader.mapKeys { "fits.${it.key}" } + mapOf(
            "schema" to "indigo-image-edit-1", "source" to prepared.source.name,
            "sourceFingerprint" to prepared.sourceFingerprint, "width" to prepared.originalWidth.toString(),
            "height" to prepared.originalHeight.toString(), "channels" to prepared.preview.channels.toString(),
            "sampleDomain" to "normalized display", "normalizationBlack" to prepared.window.black.toString(),
            "normalizationWhite" to prepared.window.white.toString(), "lp700" to settings.lp700.toString(),
            "redGain" to settings.redGain.toString(), "greenGain" to settings.greenGain.toString(),
            "blueGain" to settings.blueGain.toString(), "blackPoint" to settings.blackPoint.toString(),
            "whitePoint" to settings.whitePoint.toString(), "gamma" to settings.gamma.toString(),
            "exposureStops" to settings.exposureStops.toString(),
            "lp700Credit" to "奎木娘HIP5447 https://space.bilibili.com/455218677")
}
