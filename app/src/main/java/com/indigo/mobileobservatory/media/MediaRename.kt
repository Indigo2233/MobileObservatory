package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Renames an app-owned media file without stranding its capture or edit metadata. */
object MediaRename {
    private const val FITS_BLOCK = 2880L
    private const val FITS_CARD = 80
    private val invalidStemCharacters = Regex("""[\\/:*?"<>|]""")
    private const val MAX_STEM_LENGTH = 96

    fun normalizedStem(value: String): String {
        val stem = value.trim().trimEnd('.')
        require(stem.isNotEmpty()) { "name_empty" }
        require(stem.length <= MAX_STEM_LENGTH) { "name_too_long" }
        require(!invalidStemCharacters.containsMatchIn(stem)) { "name_invalid" }
        // A leading dot would make the file invisible to MediaLibrary (hidden/temporary prefix).
        require(stem != "." && stem != ".." && !stem.startsWith('.')) { "name_invalid" }
        return stem
    }

    /** Renames one file, preserving its extension and moving all filename-keyed sidecars. */
    fun rename(file: File, requestedStem: String,
               indexed: List<com.indigo.mobileobservatory.recording.VideoFileInfo> = emptyList(),
               onRewritten: (File) -> Unit = {}): File {
        require(file.isFile) { "rename_file" }
        require(file.extension.isNotEmpty()) { "rename_extension" }
        val stem = normalizedStem(requestedStem)
        val target = File(file.parentFile, "$stem.${file.extension}")
        if (target.absoluteFile.normalize() == file.absoluteFile.normalize()) return file
        require(!target.exists()) { "name_exists" }

        val sourceSidecar = MediaLineage.sidecar(file)
        val targetSidecar = MediaLineage.sidecar(target)
        val sourceSolveLog = com.indigo.mobileobservatory.astrometry.SolveFailureArchive.logFor(file)
        val targetSolveLog = com.indigo.mobileobservatory.astrometry.SolveFailureArchive.logFor(target)
        val sourceCapture = captureSidecar(file)
        val targetCapture = captureSidecar(target)
        val companions = listOf(
            sourceSidecar to targetSidecar,
            sourceSolveLog to targetSolveLog,
            sourceCapture to targetCapture
        ).filter { (source, target) -> source.isFile && !target.exists() }

        check(!listOf(target, targetSidecar, targetSolveLog, targetCapture).any { it.exists() }) { "name_exists" }
        val moved = mutableListOf<Pair<File, File>>()
        try {
            check(file.renameTo(target)) { "rename_failed" }
            for ((source, destination) in companions) {
                check(source.renameTo(destination)) { "rename_failed" }
                moved += source to destination
            }
            val candidates = (listOf(targetSidecar) + indexed.map { it.file } + target)
                .map(File::getAbsoluteFile)
                .filter { it.isFile }
                .distinctBy(File::getAbsolutePath)
            // Rewrite file contents first: FITS metadata edits change length/mtime, and the
            // sidecars rewritten afterwards must record the final fingerprints.
            candidates
                .filter { it.extension.equals("fit", true) || it.extension.equals("fits", true) }
                .forEach { candidate -> if (rewriteFits(candidate, file, target)) onRewritten(candidate) }
            candidates
                .mapNotNull { candidate -> MediaLineage.sidecar(candidate).takeIf(File::isFile) }
                .distinctBy(File::getAbsolutePath)
                .forEach { sidecar -> rewriteJson(sidecar, file, target) }
            return target
        } catch (failure: Throwable) {
            moved.asReversed().forEach { (source, destination) -> destination.renameTo(source) }
            if (target.exists()) target.renameTo(file)
            throw failure
        }
    }

    private fun captureSidecar(file: File): File = File(file.parentFile, "${file.name}.capture.json")

    private fun rewriteJson(sidecar: File, oldFile: File, newFile: File) {
        val json = runCatching { JSONObject(sidecar.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        if (!rewriteJsonObject(json, oldFile, newFile)) return
        val temporary = File(sidecar.parentFile, ".${sidecar.name}.rename.partial")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toString(2).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), sidecar.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    private fun rewriteJsonObject(json: JSONObject, oldFile: File, newFile: File): Boolean {
        var changed = false
        val oldAbs = oldFile.absolutePath
        val newAbs = newFile.absolutePath
        val oldCanonical = oldFile.canonicalPath
        val newCanonical = newFile.canonicalPath

        fun replacePath(text: String, prefix: String, replacement: String): String? {
            if (!text.startsWith(prefix)) return null
            val suffix = text.removePrefix(prefix)
            if (suffix.isEmpty() || suffix[0] == '|' || suffix[0] == ':') {
                changed = true
                return replacement + suffix
            }
            return null
        }

        fun value(text: String): String {
            if (text == oldFile.name) {
                changed = true
                return newFile.name
            }
            if (text == oldAbs) {
                changed = true
                return newAbs
            }
            if (text == oldCanonical) {
                changed = true
                return newCanonical
            }
            // Fingerprints embed the source's current length and mtime. Rewriting FITS
            // metadata changes both, so recompute from the renamed file instead of splicing
            // the stale tail from the old value.
            if (text.startsWith("$oldAbs:") || text.startsWith("$oldCanonical:")) {
                changed = true
                return MediaImageEditing.fingerprint(newFile)
            }
            replacePath(text, oldAbs, newAbs)?.let { return it }
            replacePath(text, oldCanonical, newCanonical)?.let { return it }
            return text
        }

        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> node.keys().asSequence().toList().forEach { key ->
                    val child = node.opt(key)
                    if (child is String) node.put(key, value(child))
                    else visit(child)
                }
                is JSONArray -> for (index in 0 until node.length()) {
                    val child = node.opt(index)
                    if (child is String) node.put(index, value(child))
                    else visit(child)
                }
            }
        }
        visit(json)
        return changed
    }

    /**
     * Stack FITS files keep the authoritative lineage in their STACKMETA/EDITMETA
     * extension. Patch it in place when the replacement fits the existing block;
     * otherwise rewrite the file through a same-directory temporary and atomically
     * replace the original.
     */
    private fun rewriteFits(file: File, oldFile: File, newFile: File): Boolean {
        val layout = RandomAccessFile(file, "r").use { reader ->
            val primary = runCatching { FitsHeaderParser.read(reader) }.getOrNull() ?: return false
            val primaryBytes = fitsDataBytes(primary) ?: return false
            val extensionStart = alignBlock(reader.filePointer + primaryBytes)
            if (extensionStart >= reader.length()) return false
            reader.seek(extensionStart)
            val extensionHeaderStart = reader.filePointer
            val extension = runCatching { FitsHeaderParser.read(reader) }.getOrNull() ?: return false
            val extensionDataStart = reader.filePointer
            FitsLayout(reader.length(), extensionStart, extensionHeaderStart, extensionDataStart, extension)
        }
        val extension = layout.extension
        if (extension["EXTNAME"] !in listOf("STACKMETA", "EDITMETA")) return false
        require(extension["BITPIX"] == "8" && extension["NAXIS"] == "1") { "rename_fits_metadata" }
        val oldSize = extension["NAXIS1"]?.toIntOrNull() ?: error("rename_fits_metadata")
        require(oldSize in 0..16 * 1024 * 1024 && layout.length - layout.extensionDataStart >= oldSize) {
            "rename_fits_metadata"
        }
        val oldPadded = alignBlock(oldSize.toLong())
        val oldEnd = layout.extensionDataStart + oldPadded
        require(oldEnd <= layout.length) { "rename_fits_metadata" }
        val (json, headerBytes) = RandomAccessFile(file, "r").use { reader ->
            val oldBytes = ByteArray(oldSize)
            reader.seek(layout.extensionDataStart)
            reader.readFully(oldBytes)
            val parsed = runCatching { JSONObject(String(oldBytes, Charsets.UTF_8)) }.getOrNull()
                ?: error("rename_fits_metadata")
            if (!rewriteJsonObject(parsed, oldFile, newFile)) return false
            val header = ByteArray((layout.extensionDataStart - layout.extensionHeaderStart).toInt())
            reader.seek(layout.extensionHeaderStart)
            reader.readFully(header)
            parsed to header
        }
        val newBytes = json.toString().toByteArray(Charsets.UTF_8)
        val newPadded = alignBlock(newBytes.size.toLong())
        check(replaceFitsCard(headerBytes, "NAXIS1", newBytes.size.toString())) { "rename_fits_metadata" }
        if (newPadded <= oldPadded) {
            RandomAccessFile(file, "rw").use { writer ->
                writer.seek(layout.extensionHeaderStart)
                writer.write(headerBytes)
                writer.seek(layout.extensionDataStart)
                writer.write(newBytes)
                writer.write(ByteArray((oldPadded - newBytes.size).toInt()))
            }
            return true
        }

        val temporary = File(file.parentFile, ".${file.name}-${UUID.randomUUID()}.rename.partial")
        try {
            BufferedInputStream(java.io.FileInputStream(file)).use { input ->
                BufferedOutputStream(FileOutputStream(temporary), 64 * 1024).use { output ->
                    copyExactly(input, output, layout.extensionStart)
                    output.write(headerBytes)
                    output.write(newBytes)
                    output.write(ByteArray((newPadded - newBytes.size).toInt()))
                    skipExactly(input, oldEnd - layout.extensionStart)
                    input.copyTo(output, 64 * 1024)
                }
            }
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return true
        } finally {
            temporary.delete()
        }
    }

    private data class FitsLayout(
        val length: Long,
        val extensionStart: Long,
        val extensionHeaderStart: Long,
        val extensionDataStart: Long,
        val extension: com.indigo.mobileobservatory.util.FitsHeader
    )

    private fun fitsDataBytes(header: com.indigo.mobileobservatory.util.FitsHeader): Long? {
        val bitpix = header["BITPIX"]?.toIntOrNull() ?: return null
        val axes = header["NAXIS"]?.toIntOrNull() ?: return null
        if (axes < 0 || axes > 4) return null
        var samples = 1L
        for (axis in 1..axes) {
            val dimension = header["NAXIS$axis"]?.toLongOrNull() ?: return null
            if (dimension <= 0) return null
            samples = Math.multiplyExact(samples, dimension)
        }
        val bytesPerSample = when (bitpix) {
            8 -> 1L
            16 -> 2L
            32, -32 -> 4L
            -64 -> 8L
            else -> return null
        }
        return Math.multiplyExact(samples, bytesPerSample)
    }

    private fun replaceFitsCard(header: ByteArray, key: String, value: String): Boolean {
        var offset = 0
        while (offset + FITS_CARD <= header.size) {
            val card = String(header, offset, FITS_CARD, Charsets.US_ASCII)
            if (card.substring(0, 8).trim() == key) {
                val replacement = (key.padEnd(8) + "= " + value.padStart(20)).padEnd(FITS_CARD).take(FITS_CARD)
                replacement.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
                return true
            }
            offset += FITS_CARD
        }
        return false
    }

    private fun copyExactly(input: InputStream, output: OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IllegalArgumentException("rename_fits_truncated")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipExactly(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) throw IllegalArgumentException("rename_fits_truncated")
            remaining--
        }
    }

    private fun alignBlock(value: Long): Long =
        Math.multiplyExact(Math.addExact(value, FITS_BLOCK - 1) / FITS_BLOCK, FITS_BLOCK)
}
