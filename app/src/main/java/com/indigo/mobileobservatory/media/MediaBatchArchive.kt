package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.recording.VideoFileInfo
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Streaming archive: bounded memory, unique relative names, sidecars, and portable relationships. */
object MediaBatchArchive {
    fun write(output: OutputStream, selected: List<VideoFileInfo>, check: () -> Unit = {},
              progress: (Int, Int) -> Unit = { _, _ -> }) {
        require(selected.isNotEmpty())
        data class Entry(val file: File, val path: String)
        val entries = mutableListOf<Entry>()
        val usedPaths = mutableSetOf<String>()
        fun add(file: File, path: String) {
            var unique = path; var suffix = 2
            while (!usedPaths.add(unique)) { unique = "$path-${suffix++}" }
            entries += Entry(file, unique)
        }
        val records = JSONArray()
        val selectedPaths = selected.map { it.file.canonicalPath }.toSet()
        val groups = MediaLineage.groups(selected, check)
        selected.distinctBy { it.file.canonicalPath }.forEachIndexed { index, info ->
            check()
            val base = "${index + 1}/" + safeName(info.name)
            if (info.file.isDirectory) {
                val root = info.file.canonicalFile
                val frames = root.listFiles().orEmpty().filter {
                    it.isFile && it.canonicalFile.parentFile == root && it.extension.lowercase() in listOf("fit", "fits") && !it.name.startsWith('.')
                }.sortedBy { it.name }
                require(frames.isNotEmpty()) { "source_changed" }
                frames.forEach { add(it, "$base/${safeName(it.name)}") }
            } else {
                require(info.file.isFile && info.file.length() == info.size) { "source_changed" }
                add(info.file, base)
                val sidecar = MediaLineage.sidecar(info.file)
                if (sidecar.isFile && sidecar.canonicalFile.parentFile == info.file.canonicalFile.parentFile) {
                    add(sidecar, "${index + 1}/${safeName(sidecar.name)}")
                }
            }
            val group = groups.first { it.members.any { member -> member.file == info.file } }
            records.put(JSONObject().put("path", base).put("format", info.format)
                .put("group", groups.indexOf(group) + 1).put("membersSelected", group.members.count { it.file.canonicalPath in selectedPaths }))
        }
        ZipOutputStream(output).use { zip ->
            val buffer = ByteArray(64 * 1024)
            entries.forEachIndexed { index, entry ->
                check()
                val length = entry.file.length(); val modified = entry.file.lastModified()
                require(entry.file.isFile && length > 0) { "source_changed" }
                zip.putNextEntry(ZipEntry(entry.path))
                entry.file.inputStream().use { input ->
                    var copied = 0L
                    while (true) {
                        check(); val count = input.read(buffer); if (count < 0) break
                        zip.write(buffer, 0, count); copied += count
                    }
                    require(copied == length && entry.file.length() == length && entry.file.lastModified() == modified) { "source_changed" }
                }
                zip.closeEntry(); progress(index + 1, entries.size)
            }
            check()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("schema", "indigo-media-batch-1").put("files", records).toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun safeName(name: String): String = name.map {
        if (it == '/' || it == '\\' || it == ':' || it.code < 32) '_' else it
    }.joinToString("").trim('.').take(180).ifBlank { "media" }
}
