package com.indigo.mobileobservatory.astrometry

import java.io.File

enum class AstapDatabase(
    val displayName: String,
    val filePrefix: String,
    val downloadUrl: String,
    val downloadSizeDescription: String,
    val minimumFreeBytes: Long
) {
    D20(
        displayName = "D20",
        filePrefix = "d20_",
        downloadUrl = "https://master.dl.sourceforge.net/project/astap-program/star_databases/d20_star_database.zip",
        downloadSizeDescription = "about 388 MB",
        minimumFreeBytes = 1_200_000_000L
    ),
    D50(
        displayName = "D50",
        filePrefix = "d50_",
        downloadUrl = "https://master.dl.sourceforge.net/project/astap-program/star_databases/d50_star_database.zip",
        downloadSizeDescription = "about 901 MB",
        minimumFreeBytes = 2_500_000_000L
    )
}

data class D50Status(
    val installed: Boolean,
    val database: AstapDatabase?,
    val fileCount: Int,
    val totalBytes: Long,
    val directory: File
)

data class DownloadProgress(
    val active: Boolean = false,
    val bytesRead: Long = 0L,
    val totalBytes: Long = -1L,
    val message: String = ""
)

data class PlateSolveResult(
    val success: Boolean,
    val message: String,
    val raDeg: Double? = null,
    val decDeg: Double? = null,
    val raHms: String? = null,
    val decDms: String? = null,
    val fovWidthDeg: Double? = null,
    val fovHeightDeg: Double? = null,
    val rotationDeg: Double? = null,
    val arcsecPerPixel: Double? = null,
    val elapsedMs: Long = 0L,
    val matchedStars: Int = 0,
    val rmsResidualDeg: Double? = null,
    val confidence: Double? = null,
    val usedImuPrior: Boolean = false,
    val wcsHeaderPath: String? = null,
    val log: String = ""
)
