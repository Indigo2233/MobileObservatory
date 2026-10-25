package com.indigo.mobileobservatory.media

import android.graphics.Bitmap
import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException

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

/** UInt16 previews and bounded source regions; PNG/FITS exports are streamed in 32-row bands. */
object MediaImageEditing {
    fun prepare(source: File, recipe: MediaEditRecipe? = null, cancelled: () -> Boolean = { false }): PreparedMediaImage {
        val key = fingerprint(source)
        require(recipe == null || recipe.sourceFingerprint == key) { "source_changed" }
        val decoded = readSource(source, 1600, cancelled)
        val window = recipe?.window ?: if (decoded.displayNormalized) decoded.image.range else MediaImageProcessing.window(decoded.image)
        require(key == fingerprint(source)) { "source_changed" }
        return PreparedMediaImage(source, key, decoded.originalWidth, decoded.originalHeight,
            decoded.image, window, recipe?.lpGains?.takeIf { it.size == 3 }?.toFloatArray()
                ?: MediaImageProcessing.lp700Gains(decoded.image, window), decoded.header)
    }

    fun isEditedFits(file: File): Boolean {
        val header = FitsHeaderParser.read(file)
        return header["EDITED"] == "T" && header["BUNIT"] in listOf("normalized display", "display intensity")
    }

    fun renderPreview(prepared: PreparedMediaImage, settings: MediaImageEditSettings,
                      cancelled: () -> Boolean = { false }): Bitmap {
        checkCancelled(cancelled)
        val result = prepared.preview.deepCopy()
        MediaImageProcessing.apply(result, settings, prepared.lpGains, prepared.window) { checkCancelled(cancelled) }
        return bitmap(result) { checkCancelled(cancelled) }
    }

    fun estimateWhiteBalance(prepared: PreparedMediaImage, settings: MediaImageEditSettings): FloatArray =
        MediaImageProcessing.whiteBalance(prepared.preview, settings.lp700, prepared.lpGains, prepared.window)

    /** Used for JPEG encoding and tests; only the ARGB bitmap is full-sized. */
    fun renderFullResolution(prepared: PreparedMediaImage, settings: MediaImageEditSettings,
                             cancelled: () -> Boolean = { false }): Bitmap {
        require(prepared.sourceFingerprint == fingerprint(prepared.source)) { "source_changed" }
        return openSource(prepared, cancelled).use { source ->
            renderBitmap(prepared, settings, source, cancelled)
        }
    }

    fun renderRegion(prepared: PreparedMediaImage, settings: MediaImageEditSettings, region: MediaImageRegion,
                     cancelled: () -> Boolean = { false }): Bitmap = openSource(prepared, cancelled).use { source ->
        val pixels = source.read(region) { checkCancelled(cancelled) }
        MediaImageProcessing.apply(pixels, settings, prepared.lpGains, prepared.window) { checkCancelled(cancelled) }
        require(prepared.sourceFingerprint == fingerprint(prepared.source)) { "source_changed" }
        bitmap(pixels) { checkCancelled(cancelled) }
    }

    private fun openSource(prepared: PreparedMediaImage, cancelled: () -> Boolean): MediaPixelSource {
        require(prepared.sourceFingerprint == fingerprint(prepared.source)) { "source_changed" }
        val source = openMediaSource(prepared.source, prepared.preview.range) { checkCancelled(cancelled) }
        if (source.width != prepared.originalWidth || source.height != prepared.originalHeight || source.channels != prepared.preview.channels) {
            source.close(); error("source_changed")
        }
        return source
    }

    private fun renderBitmap(prepared: PreparedMediaImage, settings: MediaImageEditSettings,
                             source: MediaPixelSource, cancelled: () -> Boolean): Bitmap {
        val width = source.width; val height = source.height
        MediaImageMemory.check(width.toLong() * 32 * source.channels * 2 + width * 4L + 512 * 1024,
            width.toLong() * height * 4)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(width)
            val kernel = MediaImage16Kernel(source.range, prepared.window, settings, prepared.lpGains, source.channels == 1)
            for (top in 0 until height step 32) {
                checkCancelled(cancelled)
                val band = source.read(MediaImageRegion(0, top, width, minOf(32, height - top))) { checkCancelled(cancelled) }
                kernel.apply(band) { checkCancelled(cancelled) }
                for (y in 0 until band.height) {
                    for (x in 0 until width) row[x] = argb(band, (y * width + x) * band.channels)
                    result.setPixels(row, 0, width, 0, top + y, width, 1)
                }
            }
            require(prepared.sourceFingerprint == fingerprint(prepared.source)) { "source_changed" }
            return result
        } catch (failure: Throwable) { result.recycle(); throw failure }
    }

    fun export(prepared: PreparedMediaImage, capturesDir: File, format: MediaImageExportFormat,
               settings: MediaImageEditSettings, cancelled: () -> Boolean = { false }): File {
        require(prepared.sourceFingerprint == fingerprint(prepared.source)) { "source_changed" }
        val pixels = prepared.originalWidth.toLong() * prepared.originalHeight
        val directory = File(capturesDir, if (format == MediaImageExportFormat.FIT) "FITS" else "JPG")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create the image library folder")
        val required = pixels * (if (format == MediaImageExportFormat.JPG) 4L else prepared.preview.channels * 2L) + 1024 * 1024
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
            openSource(prepared, cancelled).use { source ->
                val kernel = MediaImage16Kernel(source.range, prepared.window, settings, prepared.lpGains, source.channels == 1)
                val readBand: (MediaImageRegion) -> MediaImagePixels = { region ->
                    source.read(region) { checkCancelled(cancelled) }.also { band -> kernel.apply(band) { checkCancelled(cancelled) } }
                }
                when (format) {
                    MediaImageExportFormat.FIT -> MediaImageFitsCodec.writeBands(temporary, source.width, source.height,
                        source.channels, metadata, { checkCancelled(cancelled) }, readBand)
                    MediaImageExportFormat.PNG -> MediaPng16Codec.write(temporary, source.width, source.height,
                        source.channels, { checkCancelled(cancelled) }, readBand)
                    MediaImageExportFormat.JPG -> {
                        encoded = renderBitmap(prepared, settings, source, cancelled)
                        FileOutputStream(temporary).use { stream ->
                            if (!encoded!!.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw IOException("Image encoding failed")
                            stream.fd.sync()
                        }
                    }
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
        openMediaSource(source, check = { checkCancelled(cancelled) }).use { input ->
            val step = kotlin.math.ceil(maxOf(input.width, input.height).toDouble() / maximumSide).toInt().coerceAtLeast(1)
            DecodedMediaImage(input.read(MediaImageRegion(0, 0, input.width, input.height, step)) { checkCancelled(cancelled) },
                input.width, input.height, input.header, input.displayNormalized)
        }

    private fun argb(image: MediaImagePixels, index: Int): Int {
        fun byte(channel: Int) = (image.unsigned(index + channel) + 128) / 257
        return (0xff shl 24) or (byte(0) shl 16) or (byte(if (image.channels == 3) 1 else 0) shl 8) or byte(if (image.channels == 3) 2 else 0)
    }

    private fun bitmap(image: MediaImagePixels, check: () -> Unit): Bitmap {
        check()
        MediaImageMemory.check(image.width * 4L, image.width.toLong() * image.height * 4)
        val output = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(image.width)
            for (y in 0 until image.height) {
                check()
                for (x in 0 until image.width) {
                    val i = (y * image.width + x) * image.channels
                    row[x] = argb(image, i)
                }
                output.setPixels(row, 0, image.width, 0, y, image.width, 1)
            }
            return output
        } catch (failure: Throwable) { output.recycle(); throw failure }
    }

    internal fun checkMemory(bytes: Long) = MediaImageMemory.check(nativeBytes = bytes)
    internal fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw CancellationException("Image edit cancelled") }
    fun fingerprint(file: File): String {
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
            "sampleDomain" to "display uint16", "workingPrecision" to "uint16", "normalizationBlack" to prepared.window.black.toString(),
            "normalizationWhite" to prepared.window.white.toString(), "lp700" to settings.lp700.toString(),
            "redGain" to settings.redGain.toString(), "greenGain" to settings.greenGain.toString(),
            "blueGain" to settings.blueGain.toString(), "blackPoint" to settings.blackPoint.toString(),
            "whitePoint" to settings.whitePoint.toString(), "gamma" to settings.gamma.toString(),
            "exposureStops" to settings.exposureStops.toString(),
            "lp700Credit" to "奎木娘HIP5447 https://space.bilibili.com/455218677") +
            MediaEditRecipe.from(prepared, settings).metadata()
}
