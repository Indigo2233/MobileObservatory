package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.recording.MediaLibrary
import com.indigo.mobileobservatory.recording.VideoFileInfo
import com.indigo.mobileobservatory.stacking.StackFitsReader
import java.io.File
import org.json.JSONObject

enum class MediaRole { ORIGINAL, STACK_LINEAR, STACK_DISPLAY, EDITED }
data class MediaGroup(val id: String, val members: List<VideoFileInfo>, val roles: Map<String, MediaRole> = emptyMap())

/** Metadata is untrusted. Relationships are resolved exclusively against the visible library. */
object MediaLineage {
    private val relationshipKeys = setOf("schema", "sourceFingerprint", "sourceIdentity", "linearFits", "processedPng",
        "normalizationBlack", "normalizationWhite", "lpGains", "lp700", "redGain", "greenGain", "blueGain",
        "blackPoint", "whitePoint", "gamma", "exposureStops")
    fun metadata(file: File): Map<String, String> = runCatching {
        if (!file.isFile) return emptyMap()
        val sidecar = sidecar(file)
        if (sidecar.isFile && sidecar.length() <= 2 * 1024 * 1024) {
            val json = JSONObject(sidecar.readText())
            json.keys().asSequence().associateWith { json.get(it).toString() }
        } else if (file.extension.lowercase() in listOf("fit", "fits")) StackFitsReader.readMetadata(file)
        else emptyMap()
    }.getOrDefault(emptyMap())

    fun sidecar(file: File) = File(file.parentFile, "${file.nameWithoutExtension}.json")

    fun recipeSource(recipe: MediaEditRecipe, indexed: List<VideoFileInfo>): File? {
        return indexed.asSequence().flatMap { info ->
            if (info.file.isDirectory) info.file.listFiles().orEmpty().asSequence()
                .filter { MediaLibrary.describe(it) != null && it.canonicalFile.parentFile == info.file.canonicalFile }
            else sequenceOf(info.file)
        }.firstOrNull { file -> runCatching { MediaImageEditing.fingerprint(file) == recipe.sourceFingerprint }.getOrDefault(false) }
    }

    fun groups(indexed: List<VideoFileInfo>, check: () -> Unit = {}): List<MediaGroup> {
        val byPath = indexed.associateBy { it.file.canonicalPath }
        val metadata = indexed.associate { check(); it.file.canonicalPath to metadata(it.file).filterKeys { key -> key in relationshipKeys } }
        val parent = byPath.keys.associateWith { it }.toMutableMap()
        fun root(path: String): String { var p = path; val seen = mutableSetOf<String>()
            while (parent[p] != p && seen.add(p)) p = parent.getValue(p)
            return p
        }
        fun join(a: String, b: String) { val ra = root(a); val rb = root(b); if (ra != rb) parent[ra] = rb }
        for ((path, values) in metadata) {
            check()
            val recipe = MediaEditRecipe.parse(values)
            val source = if (recipe != null) recipeSource(recipe, indexed) else {
                val identity = values["sourceIdentity"]
                val fields = identity?.split('|')
                fields?.takeIf { it.size >= 3 }?.let { parts ->
                    byPath[parts[0]]?.file?.takeIf { it.length().toString() == parts[1] && it.lastModified().toString() == parts[2] }
                }
            }
            source?.let { original ->
                val owner = byPath[original.canonicalPath]?.file ?: indexed.firstOrNull {
                    it.file.isDirectory && original.canonicalFile.parentFile == it.file.canonicalFile
                }?.file
                owner?.let { join(path, it.canonicalPath) }
            }
            // Older stack PNG and FITS share a recipe even when the recording has been removed.
            if (values["schema"] == "indigo-stack-1") {
                val fitsName = values["linearFits"]
                val matches = indexed.filter { it.file.name == fitsName }
                if (matches.size == 1 && metadata[matches[0].file.canonicalPath]?.get("processedPng") == values["processedPng"]) {
                    join(path, matches[0].file.canonicalPath)
                }
            }
        }
        return indexed.groupBy { root(it.file.canonicalPath) }.map { (id, members) ->
            check()
            MediaGroup(id, members, members.associate { info ->
                val values = metadata.getValue(info.file.canonicalPath)
                val role = when {
                    MediaEditRecipe.parse(values) != null -> MediaRole.EDITED
                    values["schema"] == "indigo-stack-1" && info.file.extension.lowercase() in listOf("fit", "fits") -> MediaRole.STACK_LINEAR
                    values["schema"] == "indigo-stack-1" -> MediaRole.STACK_DISPLAY
                    else -> MediaRole.ORIGINAL
                }
                info.file.absolutePath to role
            })
        }
    }
}
