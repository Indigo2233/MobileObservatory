package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.astrometry.SolveFailureArchive
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.json.JSONObject

/** Keeps recipes with their image and remembers the exact app-owned restoration location. */
object MediaTrash {
    const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

    private fun captureSidecar(file: File) = File(file.parentFile, "${file.name}.capture.json")
    private fun sidecars(file: File) = listOf(MediaLineage.sidecar(file), SolveFailureArchive.logFor(file), captureSidecar(file))

    private fun marker(file: File): File {
        val key = MessageDigest.getInstance("SHA-256").digest(file.name.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(file.parentFile, ".entry-$key.json")
    }

    fun move(source: File, trash: File): Boolean = moveIf(source, trash) { true }

    internal fun moveIf(
        source: File,
        trash: File,
        commit: (() -> Boolean) -> Boolean = { it() },
        canMove: () -> Boolean
    ): Boolean = move(source, trash, uniqueName = false, commit = commit, canMove = canMove)

    internal fun moveSequenceFrame(source: File, trash: File): Boolean =
        move(source, trash, uniqueName = true) { true }

    @Synchronized
    private fun move(
        source: File,
        trash: File,
        uniqueName: Boolean,
        commit: (() -> Boolean) -> Boolean = { it() },
        canMove: () -> Boolean
    ): Boolean =
        MediaUsageRegistry.withExclusiveAccess(source) {
            canMove() && moveExclusive(source, trash, uniqueName, commit)
        } == ExclusiveAccessResult.SUCCESS

    private fun moveExclusive(
        source: File,
        trash: File,
        uniqueName: Boolean,
        commit: (() -> Boolean) -> Boolean
    ): Boolean = runCatching {
        val name = if (uniqueName)
            "${source.nameWithoutExtension.take(40)}-${UUID.randomUUID().toString().take(12)}.${source.extension}"
        else source.name
        val target = File(trash, name)
        val companions = sidecars(source).zip(sidecars(target))
        if (!source.exists() || target.exists() || companions.any { it.second.exists() }) return false
        check(trash.isDirectory || trash.mkdirs())
        val metadata = marker(target)
        if (metadata.exists()) {
            // A timestamped marker without its target is left by a stop before the rename.
            metadata.delete()
        }
        try {
            metadata.writeText(JSONObject()
                .put("original", source.canonicalPath)
                .put("trashedAtUtc", System.currentTimeMillis())
                .apply {
                    if (uniqueName) put("storedName", name)
                }.toString())
            check(commit { source.renameTo(target) })
            if (source.isFile || target.isFile) {
                companions.forEach { (from, to) -> if (from.isFile) check(from.renameTo(to)) }
            }
            true
        } catch (failure: Exception) {
            if (target.exists()) target.renameTo(source)
            companions.forEach { (from, to) -> if (to.exists()) to.renameTo(from) }
            metadata.delete()
            if (failure is CancellationException) throw failure
            false
        }
    }.getOrElse { if (it is CancellationException) throw it else false }

    @Synchronized
    fun restore(source: File, fallback: File, roots: List<File>): Boolean = runCatching {
        val metadata = marker(source)
        val entry = if (metadata.isFile && metadata.length() < 16 * 1024) JSONObject(metadata.readText()) else null
        val path = entry?.getString("original")
        val target = path?.let(::File) ?: File(fallback, source.name)
        val canonical = target.canonicalFile
        require(roots.any { root -> canonical.toPath().startsWith(root.canonicalFile.toPath()) && canonical != root.canonicalFile })
        require((target.name == source.name || entry?.optString("storedName") == source.name) && !target.exists())
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

    fun delete(source: File): Boolean = deleteIf(source) { true }

    @Synchronized
    internal fun deleteIf(
        source: File,
        commit: (() -> Boolean) -> Boolean = { it() },
        canDelete: () -> Boolean
    ): Boolean =
        MediaUsageRegistry.withExclusiveAccess(source) {
            canDelete() && deleteExclusive(source, commit)
        } == ExclusiveAccessResult.SUCCESS

    private fun deleteExclusive(source: File, commit: (() -> Boolean) -> Boolean): Boolean {
        if (!commit { source.deleteRecursively() }) return false
        sidecars(source).forEach { it.delete() }
        marker(source).delete()
        return true
    }

    /**
     * First successful trash timestamp in epoch milliseconds. Legacy entries created
     * before retention metadata existed return null and stay available for manual deletion.
     */
    @Synchronized
    fun trashedAtUtcMs(source: File): Long? = runCatching {
        val metadata = marker(source)
        if (!metadata.isFile || metadata.length() >= 16 * 1024) null else
            JSONObject(metadata.readText()).optLong("trashedAtUtc", 0L).takeIf { it > 0L }
    }.getOrNull()

    fun expiresAtUtcMs(source: File): Long? =
        trashedAtUtcMs(source)?.let { it + RETENTION_MS }

    /**
     * Deletes only entries with a valid retention timestamp. Missing or malformed
     * timestamps never cause data loss. [inUse] is evaluated immediately before deletion.
     */
    @Synchronized
    fun purgeExpired(
        trash: File,
        nowUtcMs: Long = System.currentTimeMillis(),
        inUse: (File) -> Boolean = { false }
    ): TrashPurgeResult {
        if (!trash.isDirectory) return TrashPurgeResult()
        var purged = 0
        var retained = 0
        var failed = 0
        for (entry in trash.listFiles().orEmpty()) {
            if (entry.name.startsWith(".entry-")) continue
            val trashedAt = trashedAtUtcMs(entry)
            if (trashedAt == null || nowUtcMs - trashedAt < RETENTION_MS || inUse(entry)) {
                retained++
                continue
            }
            when (MediaUsageRegistry.withExclusiveAccess(entry) {
                if (inUse(entry)) false else delete(entry)
            }) {
                ExclusiveAccessResult.SUCCESS -> purged++
                ExclusiveAccessResult.BUSY -> retained++
                ExclusiveAccessResult.FAILED -> failed++
            }
        }
        return TrashPurgeResult(purged, retained, failed)
    }
}

data class TrashPurgeResult(
    val purged: Int = 0,
    val retained: Int = 0,
    val failed: Int = 0
)
