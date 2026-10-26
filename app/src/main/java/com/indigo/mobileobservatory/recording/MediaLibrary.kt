package com.indigo.mobileobservatory.recording

import java.io.File

data class VideoFileInfo(val file: File, val name: String, val size: Long, val format: String)

enum class MediaCategory(val extensions: List<String>) {
    RECENT(emptyList()),
    PHOTOS(emptyList()),
    VIDEOS(emptyList()),
    SER_PSER(listOf("ser", "pser")),
    STACK_RESULTS(listOf("png", "fit", "fits")),
    MP4(listOf("mp4")),
    FITS(listOf("fit", "fits")),
    JPG(listOf("jpg", "jpeg", "png"))
}

/** App-owned media locations shared by the player and stacking picker.
 * File-system work is synchronous: UI callers must use Dispatchers.IO.
 */
object MediaLibrary {
    /** Single-image tasks expand FITS sessions; the media browser keeps its grouped entries by default. */
    fun list(recordingsDir: File?, capturesDir: File?, category: MediaCategory,
             excludeFile: File? = null, expandFitsFolders: Boolean = false): List<VideoFileInfo> {
        val groups = when (category) {
            MediaCategory.RECENT -> listOf(MediaCategory.JPG, MediaCategory.FITS, MediaCategory.SER_PSER, MediaCategory.MP4)
            MediaCategory.PHOTOS -> listOf(MediaCategory.JPG, MediaCategory.FITS)
            MediaCategory.VIDEOS -> listOf(MediaCategory.SER_PSER, MediaCategory.MP4)
            else -> emptyList()
        }
        if (groups.isNotEmpty()) return groups.flatMap { list(recordingsDir, capturesDir, it, excludeFile, expandFitsFolders) }
            .distinctBy { it.file.absoluteFile.normalize().path }
            .sortedWith(compareByDescending<VideoFileInfo> { it.file.lastModified() }.thenBy { it.name })
        val excluded = excludeFile?.absoluteFile?.normalize()?.path
        fun visible(file: File): Boolean = !file.name.startsWith('.') &&
            !file.name.contains(".partial", ignoreCase = true) &&
            file.absoluteFile.normalize().path != excluded
        fun matches(file: File) = visible(file) && file.isFile &&
            category.extensions.any { file.extension.equals(it, true) }
        val directories = when (category) {
            MediaCategory.SER_PSER -> listOfNotNull(recordingsDir?.resolve("SER"), recordingsDir?.resolve("PSER"))
            MediaCategory.MP4 -> listOfNotNull(recordingsDir?.resolve("MP4"))
            MediaCategory.FITS -> listOfNotNull(capturesDir?.resolve("FITS"), recordingsDir?.resolve("FITS"))
            MediaCategory.JPG -> listOfNotNull(capturesDir?.resolve("JPG"))
            MediaCategory.STACK_RESULTS -> listOfNotNull(capturesDir?.resolve("JPG"), capturesDir?.resolve("FITS"))
            else -> emptyList()
        }
        return directories.flatMap { it.listFiles().orEmpty().toList() }.filter(::visible).flatMap { file ->
            when {
                category == MediaCategory.STACK_RESULTS && !file.name.startsWith("stack-", ignoreCase = true) -> emptyList()
                matches(file) -> listOfNotNull(describe(file))
                category == MediaCategory.FITS && file.isDirectory -> {
                    val frames = file.listFiles().orEmpty().filter(::matches)
                    if (expandFitsFolders) frames.mapNotNull(::describe)
                    else if (frames.isEmpty()) emptyList()
                    else listOf(VideoFileInfo(file, file.name, frames.sumOf(File::length), "FITS"))
                }
                else -> emptyList()
            }
        }.distinctBy { it.file.absoluteFile.normalize().path }
            .sortedWith(compareByDescending<VideoFileInfo> { it.file.lastModified() }.thenBy { it.name })
    }

    /** Null for a missing/unsupported direct-open target; the browser remains usable. */
    fun describe(file: File): VideoFileInfo? {
        if (!file.isFile || file.name.startsWith('.') || file.name.contains(".partial", ignoreCase = true)) return null
        val format = file.extension.uppercase()
        if (format !in setOf("SER", "PSER", "MP4", "FIT", "FITS", "JPG", "JPEG", "PNG")) return null
        return VideoFileInfo(file, file.name, file.length(), format)
    }
}
