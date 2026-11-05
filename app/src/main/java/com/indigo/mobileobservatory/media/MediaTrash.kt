package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.astrometry.SolveFailureArchive
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Keeps recipes with their image and remembers the exact app-owned restoration location. */
object MediaTrash {
    private fun captureSidecar(file: File) = File(file.parentFile, "${file.name}.capture.json")
    private fun sidecars(file: File) = listOf(MediaLineage.sidecar(file), SolveFailureArchive.logFor(file), captureSidecar(file))

    private fun marker(file: File): File {
        val key = MessageDigest.getInstance("SHA-256").digest(file.name.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(file.parentFile, ".entry-$key.json")
    }

    fun move(source: File, trash: File): Boolean = runCatching {
        val target = File(trash, source.name)
        val companions = sidecars(source).zip(sidecars(target))
        if (!source.exists() || target.exists() || companions.any { it.second.exists() } || marker(target).exists()) return false
        check(trash.isDirectory || trash.mkdirs())
        val metadata = marker(target)
        try {
            metadata.writeText(JSONObject().put("original", source.canonicalPath).toString())
            check(source.renameTo(target))
            if (source.isFile || target.isFile) {
                companions.forEach { (from, to) -> if (from.isFile) check(from.renameTo(to)) }
            }
            true
        } catch (_: Exception) {
            if (target.exists()) target.renameTo(source)
            companions.forEach { (from, to) -> if (to.exists()) to.renameTo(from) }
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
        val companions = sidecars(source).zip(sidecars(target))
        require(companions.none { it.first.isFile && it.second.exists() })
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        check(source.renameTo(target))
        val moved = mutableListOf<Pair<File, File>>()
        for ((from, to) in companions) {
            if (!from.isFile) continue
            if (!from.renameTo(to)) {
                moved.asReversed().forEach { (old, restored) -> restored.renameTo(old) }
                target.renameTo(source)
                return false
            }
            moved += from to to
        }
        metadata.delete(); true
    }.getOrDefault(false)

    fun delete(source: File) {
        if (source.deleteRecursively()) { sidecars(source).forEach { it.delete() }; marker(source).delete() }
    }
}
