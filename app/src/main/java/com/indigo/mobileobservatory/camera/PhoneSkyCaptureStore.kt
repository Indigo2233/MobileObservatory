package com.indigo.mobileobservatory.camera

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scratch storage for M0 sky captures. RAW DNGs are large, so the directory is capped by both
 * file count and total bytes; the oldest captures are evicted after every shot.
 */
object PhoneSkyCaptureStore {
    private const val DIR_NAME = "phone_sky_m0"
    const val DEFAULT_MAX_FILES = 24
    const val DEFAULT_MAX_BYTES = 300L * 1024 * 1024

    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null), DIR_NAME).also { it.mkdirs() }

    fun newBaseName(): String =
        "sky_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun files(context: Context): List<File> =
        directory(context).listFiles()?.filter { it.isFile }.orEmpty()

    fun usageBytes(context: Context): Long = files(context).sumOf { it.length() }

    /** Deletes oldest files until both caps are satisfied. Returns bytes reclaimed. */
    fun enforceRetention(
        context: Context,
        maxFiles: Int = DEFAULT_MAX_FILES,
        maxBytes: Long = DEFAULT_MAX_BYTES
    ): Long {
        val sorted = files(context).sortedByDescending { it.lastModified() }
        var kept = 0
        var keptBytes = 0L
        var reclaimed = 0L
        for (file in sorted) {
            val size = file.length()
            val overflow = kept >= maxFiles || keptBytes + size > maxBytes
            if (overflow && file.delete()) {
                reclaimed += size
            } else {
                kept++
                keptBytes += size
            }
        }
        return reclaimed
    }

    fun clear(context: Context): Long {
        var reclaimed = 0L
        for (file in files(context)) {
            val size = file.length()
            if (file.delete()) reclaimed += size
        }
        return reclaimed
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
