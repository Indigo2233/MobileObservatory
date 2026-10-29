package com.indigo.mobileobservatory.astrometry

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Downloads one pinned vendor archive, trying the next source after an I/O or integrity failure. */
internal object AstapArchiveDownloader {
    suspend fun download(
        urls: List<String>,
        destination: File,
        expectedBytes: Long,
        expectedSha256: String,
        name: String,
        progress: (DownloadProgress) -> Unit
    ) {
        require(urls.isNotEmpty() && expectedBytes > 0 && expectedSha256.matches(Regex("[0-9a-f]{64}")))
        var lastFailure: IOException? = null
        for ((index, url) in urls.withIndex()) {
            coroutineContext.ensureActive()
            try {
                val message = if (index == 0) "Downloading $name" else "Downloading $name (backup source)"
                downloadOne(url, destination, expectedBytes, expectedSha256, name, message, progress)
                return
            } catch (e: IOException) {
                lastFailure = e
                destination.delete()
            }
        }
        throw IOException("$name download failed from all sources", lastFailure)
    }

    private suspend fun downloadOne(
        url: String,
        destination: File,
        expectedBytes: Long,
        expectedSha256: String,
        name: String,
        message: String,
        progress: (DownloadProgress) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MobileObservatory/1.0")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("$name download failed: HTTP ${connection.responseCode}")
            }
            val contentLength = connection.contentLengthLong
            if (contentLength >= 0 && contentLength != expectedBytes) {
                throw IOException("$name archive length differs from expected size")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            progress(DownloadProgress(active = true, totalBytes = expectedBytes, message = message))
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        done += read
                        if (done > expectedBytes) throw IOException("$name archive exceeds expected size")
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        progress(DownloadProgress(active = true, bytesRead = done, totalBytes = expectedBytes, message = message))
                    }
                }
            }
            coroutineContext.ensureActive()
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (done != expectedBytes || actualSha256 != expectedSha256) {
                throw IOException("$name archive integrity check failed")
            }
        } finally {
            connection.disconnect()
        }
    }
}
