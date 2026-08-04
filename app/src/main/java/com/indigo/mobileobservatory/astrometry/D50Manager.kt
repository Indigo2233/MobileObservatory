package com.indigo.mobileobservatory.astrometry

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

class D50Manager(private val context: Context) {
    companion object {
        private const val D50_URL =
            "https://master.dl.sourceforge.net/project/astap-program/star_databases/d50_star_database.zip"
        private const val MIN_FREE_BYTES = 2_500_000_000L
    }

    val astapDir: File
        get() = File(context.filesDir, "astap").also { it.mkdirs() }

    private val legacyExternalAstapDir: File?
        get() = context.getExternalFilesDir(null)?.let { File(it, "astap") }

    fun status(): D50Status {
        migrateLegacyDatabaseIfNeeded()
        val files = astapDir.listFiles { file ->
            file.isFile && file.name.startsWith("d50_", ignoreCase = true) &&
                file.name.endsWith(".1476", ignoreCase = true)
        }.orEmpty()
        return D50Status(
            installed = files.isNotEmpty(),
            fileCount = files.size,
            totalBytes = files.sumOf { it.length() },
            directory = astapDir
        )
    }

    fun hasEnoughSpace(): Boolean {
        val stat = StatFs(astapDir.absolutePath)
        return stat.availableBytes >= MIN_FREE_BYTES
    }

    suspend fun downloadAndInstall(progress: (DownloadProgress) -> Unit) {
        if (!hasEnoughSpace()) {
            error("Need at least 2.5 GB free space for D50 download and extraction.")
        }

        astapDir.mkdirs()
        val zipFile = File(astapDir, "d50_star_database.zip.download")
        val tmpDir = File(astapDir, "d50_tmp")
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        try {
            downloadZip(zipFile, progress)
            progress(DownloadProgress(active = true, message = "Extracting D50..."))
            unzip(zipFile, tmpDir)

            val extracted = tmpDir.walkTopDown()
                .filter { it.isFile && it.name.startsWith("d50_", true) && it.name.endsWith(".1476", true) }
                .toList()
            if (extracted.isEmpty()) error("D50 database files were not found in the downloaded archive.")

            astapDir.listFiles { file ->
                file.isFile && file.name.startsWith("d50_", true) && file.name.endsWith(".1476", true)
            }?.forEach { it.delete() }

            extracted.forEach { source ->
                val target = File(astapDir, source.name)
                if (target.exists()) target.delete()
                source.copyTo(target, overwrite = true)
            }
            progress(DownloadProgress(active = false, message = "D50 installed: ${extracted.size} files"))
        } finally {
            zipFile.delete()
            if (tmpDir.exists()) tmpDir.deleteRecursively()
        }
    }

    fun deleteDatabase() {
        astapDir.listFiles { file ->
            file.isFile && file.name.startsWith("d50_", true) && file.name.endsWith(".1476", true)
        }?.forEach { it.delete() }
    }

    private fun migrateLegacyDatabaseIfNeeded() {
        val currentFiles = astapDir.listFiles { file ->
            file.isFile && file.name.startsWith("d50_", true) && file.name.endsWith(".1476", true)
        }.orEmpty()
        if (currentFiles.isNotEmpty()) return

        val legacy = legacyExternalAstapDir ?: return
        val legacyFiles = legacy.listFiles { file ->
            file.isFile && file.name.startsWith("d50_", true) && file.name.endsWith(".1476", true)
        }.orEmpty()
        if (legacyFiles.isEmpty()) return

        astapDir.mkdirs()
        legacyFiles.forEach { source ->
            val target = File(astapDir, source.name)
            if (!target.exists() || target.length() != source.length()) {
                source.copyTo(target, overwrite = true)
            }
        }
    }

    private suspend fun downloadZip(zipFile: File, progress: (DownloadProgress) -> Unit) {
        val connection = (URL(D50_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MobileObservatory/1.0")
        }
        try {
            val total = connection.contentLengthLong
            if (connection.responseCode !in 200..299) {
                error("D50 download failed: HTTP ${connection.responseCode}")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } >= 0) {
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, read)
                        done += read
                        progress(
                            DownloadProgress(
                                active = true,
                                bytesRead = done,
                                totalBytes = total,
                                message = "Downloading D50"
                            )
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                val outFile = File(targetDir, entry.name).canonicalFile
                if (!outFile.path.startsWith(targetDir.canonicalPath)) {
                    error("Unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }
}
