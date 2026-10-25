package com.indigo.mobileobservatory.media

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Keeps recipes with their image and remembers the exact app-owned restoration location. */
object MediaTrash {
    private fun marker(file: File): File {
        val key = MessageDigest.getInstance("SHA-256").digest(file.name.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(file.parentFile, ".entry-$key.json")
    }

    fun move(source: File, trash: File): Boolean = runCatching {
        val target = File(trash, source.name)
        val sourceSidecar = MediaLineage.sidecar(source)
        val targetSidecar = MediaLineage.sidecar(target)
        if (!source.exists() || target.exists() || targetSidecar.exists() || marker(target).exists()) return false
        check(trash.isDirectory || trash.mkdirs())
        val metadata = marker(target)
        try {
            metadata.writeText(JSONObject().put("original", source.canonicalPath).toString())
            check(source.renameTo(target))
            if (source.isFile || target.isFile) {
                if (sourceSidecar.isFile) check(sourceSidecar.renameTo(targetSidecar))
            }
            true
        } catch (_: Exception) {
            if (target.exists()) target.renameTo(source)
            if (targetSidecar.exists()) targetSidecar.renameTo(sourceSidecar)
            metadata.delete(); false
        }
    }.getOrDefault(false)

    fun restore(source: File, fallback: File, roots: List<File>): Boolean = runCatching {
        val metadata = marker(source)
        val path = if (metadata.isFile && metadata.length() < 16 * 1024) JSONObject(metadata.readText()).getString("original") else null
        val target = path?.let(::File) ?: File(fallback, source.name)
        val canonical = target.canonicalFile
        require(roots.any { root -> canonical.toPath().startsWith(root.canonicalFile.toPath()) && canonical != root.canonicalFile })
        require(target.name == source.name && !target.exists())
        val fromSidecar = MediaLineage.sidecar(source); val toSidecar = MediaLineage.sidecar(target)
        require(!fromSidecar.isFile || !toSidecar.exists())
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        check(source.renameTo(target))
        if (fromSidecar.isFile && !fromSidecar.renameTo(toSidecar)) { target.renameTo(source); return false }
        metadata.delete(); true
    }.getOrDefault(false)

    fun delete(source: File) {
        if (source.deleteRecursively()) { MediaLineage.sidecar(source).delete(); marker(source).delete() }
    }
}
