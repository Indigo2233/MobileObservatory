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
    val astapDir: File
        get() = File(context.filesDir, "astap").also { it.mkdirs() }

    private val legacyExternalAstapDir: File?
        get() = context.getExternalFilesDir(null)?.let { File(it, "astap") }

    fun status(): D50Status {
        migrateLegacyDatabaseIfNeeded()
        val installedDatabase = AstapDatabase.entries.firstOrNull { database -> databaseFiles(database).isNotEmpty() }
        val files = installedDatabase?.let(::databaseFiles).orEmpty()
        return D50Status(
            installed = files.isNotEmpty(),
            database = installedDatabase,
            fileCount = files.size,
            totalBytes = files.sumOf { it.length() },
            directory = astapDir
        )
    }

    fun hasEnoughSpace(database: AstapDatabase): Boolean {
        val stat = StatFs(astapDir.absolutePath)
        return stat.availableBytes >= database.minimumFreeBytes
    }

    suspend fun downloadAndInstall(database: AstapDatabase, progress: (DownloadProgress) -> Unit) {
        val installed = status().database
        if (installed != null && installed != database) {
            error("Delete the installed ${installed.displayName} database before downloading ${database.displayName}.")
        }
        if (!hasEnoughSpace(database)) {
            error("Need at least ${database.minimumFreeBytes / 1_000_000_000.0} GB free space for ${database.displayName} download and extraction.")
        }

        astapDir.mkdirs()
        val zipFile = File(astapDir, "${database.displayName.lowercase()}_star_database.zip.download")
        val tmpDir = File(astapDir, "${database.displayName.lowercase()}_tmp")
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        try {
            downloadZip(zipFile, database, progress)
            progress(DownloadProgress(active = true, message = "Extracting ${database.displayName}..."))
            unzip(zipFile, tmpDir)

            val extracted = tmpDir.walkTopDown()
                .filter { it.isFile && isDatabaseFile(it, database) }
                .toList()
            if (extracted.isEmpty()) error("${database.displayName} database files were not found in the downloaded archive.")

            databaseFiles(database).forEach { it.delete() }

            extracted.forEach { source ->
                val target = File(astapDir, source.name)
                if (target.exists()) target.delete()
                source.copyTo(target, overwrite = true)
            }
            progress(DownloadProgress(active = false, message = "${database.displayName} installed: ${extracted.size} files"))
        } finally {
            zipFile.delete()
            if (tmpDir.exists()) tmpDir.deleteRecursively()
        }
    }

    fun deleteDatabase() {
        AstapDatabase.entries.forEach { database -> databaseFiles(database).forEach { it.delete() } }
    }

    private fun migrateLegacyDatabaseIfNeeded() {
        val currentFiles = AstapDatabase.entries.flatMap(::databaseFiles)
        if (currentFiles.isNotEmpty()) return

        val legacy = legacyExternalAstapDir ?: return
        val legacyFiles = legacy.listFiles { file ->
            isDatabaseFile(file, AstapDatabase.D50)
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

    private suspend fun downloadZip(zipFile: File, database: AstapDatabase, progress: (DownloadProgress) -> Unit) {
        val connection = (URL(database.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MobileObservatory/1.0")
        }
        try {
            val total = connection.contentLengthLong
            if (connection.responseCode !in 200..299) {
                error("${database.displayName} download failed: HTTP ${connection.responseCode}")
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
                                message = "Downloading ${database.displayName}"
                            )
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun databaseFiles(database: AstapDatabase): List<File> = astapDir.listFiles { file ->
        isDatabaseFile(file, database)
    }?.toList().orEmpty()

    private fun isDatabaseFile(file: File, database: AstapDatabase): Boolean =
        file.isFile && file.name.startsWith(database.filePrefix, true) && file.name.endsWith(".1476", true)

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
