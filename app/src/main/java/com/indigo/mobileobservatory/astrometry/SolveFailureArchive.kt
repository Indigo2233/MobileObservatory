package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CancellationException

/** Bounded, byte-for-byte evidence copies in the existing photo library. */
internal object SolveFailureArchive {
    private const val PREFIX = "solve-failed-"
    private const val MAX_ENTRIES = 8
    private const val MAX_BYTES = 256L * 1024 * 1024
    private val imageExtensions = setOf("fits", "fit", "jpg", "jpeg", "png")

    data class Saved(val image: File, val log: File)

    fun logFor(image: File): File = File(image.parentFile, "${image.name}.solve.txt")

    /** Returns null for explicitly marked simulation images, without consuming archive capacity. */
    @Synchronized fun save(capturesDir: File, source: File, diagnostics: String,
        maxEntries: Int = MAX_ENTRIES, maxBytes: Long = MAX_BYTES,
        checkActive: () -> Unit = {}): Saved? {
        checkActive()
        require(maxEntries > 0 && maxBytes > 0)
        val extension = source.extension.lowercase()
        require(source.isFile && extension in imageExtensions) { "Unsupported solve evidence image" }
        if (extension in setOf("fit", "fits") && isSimulation(source)) return null
        val size = source.length()
        val modified = source.lastModified()
        val logBytes = diagnostics.toByteArray(Charsets.UTF_8)
        require(size > 0 && size + logBytes.size <= maxBytes) { "Solve evidence exceeds the archive limit" }
        val directory = File(capturesDir, if (extension in setOf("fit", "fits")) "FITS" else "JPG")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create solve evidence directory" }
        val reuseSource = source.name.startsWith(PREFIX) && logFor(source).isFile &&
            source.canonicalFile.parentFile == directory.canonicalFile
        val image = if (reuseSource) source else File(directory, "$PREFIX${System.currentTimeMillis()}-${UUID.randomUUID()}.$extension")
        val log = logFor(image)
        val partialImage = File(directory, ".${image.name}.partial")
        val partialLog = File(directory, ".${log.name}.partial")
        try {
            if (!reuseSource) {
                source.inputStream().use { input -> partialImage.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } }
            }
            check((reuseSource || partialImage.length() == size) && source.length() == size && source.lastModified() == modified) {
                "Solve evidence source changed while copying"
            }
            partialLog.writeBytes(logBytes)
            checkActive()
            Files.move(partialLog.toPath(), log.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            if (!reuseSource) check(partialImage.renameTo(image)) { "Cannot publish solve evidence image" }
            val previous = listOf("FITS", "JPG").flatMap { File(capturesDir, it).listFiles().orEmpty().toList() }
                .filter { it != image && it.isFile && it.name.startsWith(PREFIX) &&
                    it.extension.lowercase() in imageExtensions && logFor(it).isFile }
                .sortedByDescending { it.lastModified() }
            var retainedBytes = image.length() + log.length()
            var retainedCount = 1
            previous.forEach { older ->
                val oldLog = logFor(older)
                val bytes = older.length() + oldLog.length()
                if (retainedCount < maxEntries && retainedBytes + bytes <= maxBytes) {
                    retainedCount++
                    retainedBytes += bytes
                } else if (older.delete()) oldLog.delete()
            }
            return Saved(image, log)
        } catch (failure: Throwable) {
            if (!reuseSource) { image.delete(); log.delete() }
            throw failure
        } finally {
            partialImage.delete(); partialLog.delete()
        }
    }

    private fun isSimulation(source: File): Boolean {
        val imageType = try {
            FitsHeaderParser.read(source)["IMAGETYP"]
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Damaged real images are useful evidence even when their FITS header cannot be read.
            null
        }
        return imageType?.trim().equals("SIMULATION", ignoreCase = true)
    }
}
