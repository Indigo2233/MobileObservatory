package com.indigo.mobileobservatory.astrometry

import java.io.File
import java.nio.file.Files

/** ASTAP's -o directs both sidecars into a new attempt directory; the image stays in place. */
internal fun createAstapOutputBase(cacheDir: File): File =
    File(Files.createTempDirectory(cacheDir.toPath(), "astap_solve_").toFile(), "solution")

internal fun astapOutputFiles(outputBase: File): List<File> =
    listOf("wcs", "ini").map { File(outputBase.parentFile, "${outputBase.name}.$it") }.filter { it.isFile }

internal fun parseAstapSolution(exitCode: Int, files: List<File>, width: Int, height: Int): ParsedWcs? {
    if (exitCode != 0) return null
    val summary = files.firstOrNull { it.extension.equals("ini", true) } ?: return null
    if (!FitsHeaderReader.read(summary)["PLTSOLVD"].equals("T", true)) return null
    val related = files.filter {
        it.absoluteFile.parentFile == summary.absoluteFile.parentFile &&
            it.nameWithoutExtension == summary.nameWithoutExtension &&
            (it.extension.equals("ini", true) || it.extension.equals("wcs", true))
    }
    return FitsWcsParser.parseCandidates(related, width, height)
}
