package com.indigo.mobileobservatory.media

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.indigo.mobileobservatory.recording.SERReader
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class ExternalMediaImportError { MISSING_FILE, UNSUPPORTED, READ_PERMISSION, NO_SPACE, TOO_LARGE, INVALID_FILE, COPY_FAILED }
class ExternalMediaImportException(val reason: ExternalMediaImportError) : Exception(reason.name)

/** Copies a granted content URI read-only into the existing media library. Never edits its source. */
object ExternalMediaImporter {
    private const val RESERVE_BYTES = 256L * 1024 * 1024
    private const val MAX_SER_BYTES = 8L * 1024 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 128L * 1024 * 1024
    private const val MAX_FITS_BYTES = 32L * 1024 * 1024
    private val extensions = setOf("ser", "pser", "fit", "fits", "jpg", "jpeg", "png")
    private val mimeExtensions = mapOf("application/x-ser" to "ser", "application/x-pser" to "pser",
        "application/fits" to "fits", "image/fits" to "fits", "image/jpeg" to "jpg", "image/png" to "png")

    fun contentUri(intent: Intent): Uri? {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        return uri?.takeIf { it.scheme == "content" }
    }

    suspend fun importFile(context: Context, intent: Intent): File {
        val uri = contentUri(intent) ?: throw ExternalMediaImportException(ExternalMediaImportError.MISSING_FILE)
        val resolver = context.contentResolver
        var displayName: String? = null; var reportedSize: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)?.take(256)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) reportedSize = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                }
            }
            val mime = resolver.getType(uri)?.lowercase() ?: intent.type?.lowercase()
            val extension = supportedExtension(displayName ?: uri.lastPathSegment, mime)
                ?: throw ExternalMediaImportException(ExternalMediaImportError.UNSUPPORTED)
            val limit = when (extension) { "ser", "pser" -> MAX_SER_BYTES; "fit", "fits" -> MAX_FITS_BYTES; else -> MAX_IMAGE_BYTES }
            if (reportedSize?.let { it > limit } == true) throw ExternalMediaImportException(ExternalMediaImportError.TOO_LARGE)
            val root = context.getExternalFilesDir(if (extension in setOf("ser", "pser")) "recordings" else "captures")
                ?: throw ExternalMediaImportException(ExternalMediaImportError.NO_SPACE)
            val directory = File(root, when (extension) { "ser" -> "SER"; "pser" -> "PSER"; "fit", "fits" -> "FITS"; else -> "JPG" })
            if (!directory.isDirectory && !directory.mkdirs()) throw ExternalMediaImportException(ExternalMediaImportError.NO_SPACE)
            val budget = minOf(limit, (directory.usableSpace - RESERVE_BYTES).coerceAtLeast(0))
            if (budget <= 0 || reportedSize?.let { it > budget } == true) throw ExternalMediaImportException(ExternalMediaImportError.NO_SPACE)
            val stem = (displayName ?: "file").substringBeforeLast('.').map { character ->
                if (character.isISOControl() || character in "/\\:*?\"<>|") '_' else character
            }.joinToString("").replace(Regex("\\.partial", RegexOption.IGNORE_CASE), "_partial")
                .trim(' ', '.', '_').take(70).ifBlank { "file" }
            val token = UUID.randomUUID().toString()
            val partial = File(directory, ".partial-import-$token")
            val result = File(directory, "$stem-import-$token.$extension")
            val coroutine = currentCoroutineContext()
            try {
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var count = 0L
                        while (true) {
                            coroutine.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            count += read
                            if (count > budget) throw ExternalMediaImportException(if (budget < limit) ExternalMediaImportError.NO_SPACE else ExternalMediaImportError.TOO_LARGE)
                            if (directory.usableSpace < RESERVE_BYTES + read) throw ExternalMediaImportException(ExternalMediaImportError.NO_SPACE)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                } ?: throw ExternalMediaImportException(ExternalMediaImportError.READ_PERMISSION)
                coroutine.ensureActive()
                validateContent(partial, extension)
                coroutine.ensureActive()
                if (!partial.renameTo(result)) throw ExternalMediaImportException(ExternalMediaImportError.COPY_FAILED)
                return result
            } finally {
                // Only this import's unpublished app-owned temporary file is removed.
                if (partial.exists()) partial.delete()
            }
        } catch (permission: SecurityException) {
            throw ExternalMediaImportException(ExternalMediaImportError.READ_PERMISSION)
        }
    }

    internal fun supportedExtension(displayName: String?, mime: String?): String? {
        val suffix = displayName?.substringAfterLast('.', "")?.lowercase()
        if (suffix in extensions) return suffix
        // Do not accept arbitrary binary files just because a caller supplies an octet-stream MIME.
        return mimeExtensions[mime]
    }

    private fun validateContent(file: File, extension: String) {
        when (extension) {
            "ser", "pser" -> SERReader(file).use { reader ->
                if (!reader.open() || reader.header?.frameCount?.let { it > 0 } != true ||
                    reader.header?.isPSER != (extension == "pser")) throw ExternalMediaImportException(ExternalMediaImportError.INVALID_FILE)
            }
            "fit", "fits" -> validateFits(file)
            else -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val expected = if (extension == "png") "image/png" else "image/jpeg"
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType != expected)
                    throw ExternalMediaImportException(ExternalMediaImportError.INVALID_FILE)
            }
        }
    }

    /** The legacy integer FITS preview materializes its input, so impose an explicit preview budget. */
    private fun validateFits(file: File) = RandomAccessFile(file, "r").use { input ->
        val cards = mutableMapOf<String, String>(); var ended = false
        val card = ByteArray(80)
        while (!ended && input.filePointer + 80 <= input.length() && input.filePointer < 1_048_576) {
            input.readFully(card)
            val text = String(card, Charsets.US_ASCII); val key = text.take(8).trim()
            if (key == "END") ended = true
            else if (text.getOrNull(8) == '=') cards[key] = text.substring(10).substringBefore('/').trim()
        }
        val width = cards["NAXIS1"]?.toLongOrNull() ?: 0
        val height = cards["NAXIS2"]?.toLongOrNull() ?: 0
        val axes = cards["NAXIS"]?.toIntOrNull() ?: 0
        val channels = if (axes == 3) cards["NAXIS3"]?.toLongOrNull() ?: 0 else 1
        val bitpix = cards["BITPIX"]?.toIntOrNull() ?: 0
        if (!ended || cards["SIMPLE"] != "T" || axes !in 2..3 || width <= 0 || height <= 0 ||
            channels !in setOf(1L, 3L) || bitpix !in setOf(8, 16, 32, -32) || width > 8_000_000L / height)
            throw ExternalMediaImportException(ExternalMediaImportError.INVALID_FILE)
        val bytes = width * height * channels * kotlin.math.abs(bitpix) / 8
        val offset = ((input.filePointer + 2879) / 2880) * 2880
        if (bytes > MAX_FITS_BYTES || offset + bytes > input.length()) throw ExternalMediaImportException(ExternalMediaImportError.INVALID_FILE)
    }
}
