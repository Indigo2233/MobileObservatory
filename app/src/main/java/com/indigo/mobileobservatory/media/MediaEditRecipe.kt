package com.indigo.mobileobservatory.media

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

/** Parameters always refer to the original samples, including the preview's shared window. */
data class MediaEditRecipe(
    val sourceFingerprint: String,
    val settings: MediaImageEditSettings,
    val window: MediaImageWindow,
    val lpGains: List<Float>
) {
    fun metadata(): Map<String, String> = mapOf(
        "schema" to SCHEMA, "workingPrecision" to "uint16", "sourceFingerprint" to sourceFingerprint,
        "normalizationBlack" to window.black.toString(), "normalizationWhite" to window.white.toString(),
        "lpGains" to lpGains.joinToString(","), "lp700" to settings.lp700.toString(),
        "redGain" to settings.redGain.toString(), "greenGain" to settings.greenGain.toString(),
        "blueGain" to settings.blueGain.toString(), "blackPoint" to settings.blackPoint.toString(),
        "whitePoint" to settings.whitePoint.toString(), "gamma" to settings.gamma.toString(),
        "exposureStops" to settings.exposureStops.toString())

    companion object {
        const val SCHEMA = "indigo-image-edit-1"
        fun from(prepared: PreparedMediaImage, settings: MediaImageEditSettings) =
            MediaEditRecipe(prepared.sourceFingerprint, settings, prepared.window, prepared.lpGains.toList())

        fun parse(values: Map<String, String>): MediaEditRecipe? = runCatching {
            require(values["schema"] == SCHEMA)
            fun number(key: String, range: ClosedFloatingPointRange<Float>): Float =
                values.getValue(key).toFloat().also { require(it.isFinite() && it in range) }
            val settings = MediaImageEditSettings(
                lp700 = values.getValue("lp700").toBooleanStrict(),
                redGain = number("redGain", .5f..2f), greenGain = number("greenGain", .5f..2f),
                blueGain = number("blueGain", .5f..2f), blackPoint = number("blackPoint", 0f.. .3f),
                whitePoint = number("whitePoint", .7f..1f), gamma = number("gamma", .5f..2f),
                exposureStops = number("exposureStops", -3f..3f))
            val gains = values["lpGains"]?.split(',')?.map(String::toFloat).orEmpty()
            // Older exports did not save these gains; they are re-estimated from the original.
            require(gains.isEmpty() || gains.size == 3 && gains.all { it.isFinite() && it > 0f && it <= 64f })
            MediaEditRecipe(values.getValue("sourceFingerprint"), settings,
                MediaImageWindow(values.getValue("normalizationBlack").toFloat(),
                    values.getValue("normalizationWhite").toFloat()), gains)
        }.getOrNull()
    }
}

/** Explicitly saved recipes survive process death. Each file is replaced atomically. */
class MediaEditRecipeStore(private val directory: File) {
    private fun file(source: File): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.canonicalPath.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it.toInt() and 255) } + ".json")
    }
    fun load(source: File): MediaEditRecipe? = runCatching {
        val saved = file(source)
        if (!saved.isFile || saved.length() > 64 * 1024) return null
        val json = JSONObject(saved.readText())
        MediaEditRecipe.parse(json.keys().asSequence().associateWith { json.getString(it) })
            ?.takeIf { it.sourceFingerprint == MediaImageEditing.fingerprint(source) }
    }.getOrNull()

    fun save(prepared: PreparedMediaImage, settings: MediaImageEditSettings) {
        require(prepared.sourceFingerprint == MediaImageEditing.fingerprint(prepared.source)) { "source_changed" }
        check(directory.isDirectory || directory.mkdirs())
        val destination = file(prepared.source)
        val temporary = File(directory, ".${UUID.randomUUID()}.partial")
        try {
            FileOutputStream(temporary).use {
                it.write(JSONObject(MediaEditRecipe.from(prepared, settings).metadata()).toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

/** One undo step per committed gesture; continuous slider updates remain one transaction. */
data class MediaEditHistory(
    val current: MediaImageEditSettings = MediaImageEditSettings(),
    val past: List<MediaImageEditSettings> = emptyList(),
    val future: List<MediaImageEditSettings> = emptyList(),
    val gestureStart: MediaImageEditSettings? = null
) {
    fun update(value: MediaImageEditSettings) = copy(current = value, gestureStart = gestureStart ?: current)
    fun commit(): MediaEditHistory {
        val start = gestureStart ?: return this
        return if (start == current) copy(gestureStart = null) else
            copy(past = (past + start).takeLast(80), future = emptyList(), gestureStart = null)
    }
    fun change(value: MediaImageEditSettings) = commit().update(value).commit()
    fun undo(): MediaEditHistory {
        val committed = commit()
        return if (committed.past.isEmpty()) committed else MediaEditHistory(
            committed.past.last(), committed.past.dropLast(1), listOf(committed.current) + committed.future)
    }
    fun redo(): MediaEditHistory {
        val committed = commit()
        return if (committed.future.isEmpty()) committed else MediaEditHistory(
            committed.future.first(), committed.past + committed.current, committed.future.drop(1))
    }
}
