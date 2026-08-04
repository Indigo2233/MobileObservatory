package com.indigo.mobileobservatory.astrometry

import java.io.File

data class D50Status(
    val installed: Boolean,
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
    val wcsHeaderPath: String? = null,
    val log: String = ""
)
