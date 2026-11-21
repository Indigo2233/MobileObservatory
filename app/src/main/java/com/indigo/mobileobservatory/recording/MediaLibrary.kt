package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.media.MediaLineage
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
    /** Browser categories keep stills, recording sessions and stack outputs separate. */
    fun browse(recordingsDir: File?, capturesDir: File?, category: MediaCategory,
               excludeFile: File? = null): List<VideoFileInfo> = when (category) {
        MediaCategory.PHOTOS, MediaCategory.STACK_RESULTS -> list(recordingsDir, capturesDir, MediaCategory.PHOTOS, excludeFile)
            .filter { it.file.isFile && isStackResult(it.file) == (category == MediaCategory.STACK_RESULTS) }
        MediaCategory.VIDEOS -> list(recordingsDir, capturesDir, MediaCategory.RECENT, excludeFile)
            .filter { it.format in videoFormats || it.file.isDirectory }
        else -> list(recordingsDir, capturesDir, category, excludeFile)
    }

    private val videoFormats = setOf("SER", "PSER", "MP4")
    private fun isStackResult(file: File): Boolean =
        file.extension.lowercase() in MediaCategory.STACK_RESULTS.extensions &&
            (file.name.startsWith("stack-", ignoreCase = true) || MediaLineage.metadata(file)["schema"] == "indigo-stack-1")

    /** Individual still images, including completed frames within FITS recordings. */
    fun listImages(recordingsDir: File?, capturesDir: File?, excludeFile: File? = null): List<VideoFileInfo> =
        list(recordingsDir, capturesDir, MediaCategory.PHOTOS, excludeFile).flatMap { info ->
            if (info.file.isDirectory) {
                info.file.listFiles().orEmpty().filter {
                    it.absoluteFile.normalize() != excludeFile?.absoluteFile?.normalize() &&
                        (it.extension.equals("fit", true) || it.extension.equals("fits", true))
                }.mapNotNull { describe(it)?.copy(name = "${info.name} / ${it.name}") }
            } else listOf(info)
        }.distinctBy { it.file.absoluteFile.normalize().path }
            .sortedWith(compareByDescending<VideoFileInfo> { it.file.lastModified() }.thenBy { it.name })

    fun list(recordingsDir: File?, capturesDir: File?, category: MediaCategory,
             excludeFile: File? = null): List<VideoFileInfo> {
        val groups = when (category) {
            MediaCategory.RECENT -> listOf(MediaCategory.JPG, MediaCategory.FITS, MediaCategory.SER_PSER, MediaCategory.MP4)
            MediaCategory.PHOTOS -> listOf(MediaCategory.JPG, MediaCategory.FITS)
            MediaCategory.VIDEOS -> listOf(MediaCategory.SER_PSER, MediaCategory.MP4)
            else -> emptyList()
        }
        if (groups.isNotEmpty()) return groups.flatMap { list(recordingsDir, capturesDir, it, excludeFile) }
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
        return directories.flatMap { it.listFiles().orEmpty().toList() }.filter(::visible).mapNotNull { file ->
            when {
                category == MediaCategory.STACK_RESULTS && !file.name.startsWith("stack-", ignoreCase = true) -> null
                matches(file) -> describe(file)
                category == MediaCategory.FITS && file.isDirectory -> {
                    val frames = file.listFiles().orEmpty().filter(::matches)
                    if (frames.isEmpty()) null else VideoFileInfo(file, file.name, frames.sumOf(File::length), "FITS")
                }
                else -> null
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
