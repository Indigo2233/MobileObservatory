package com.indigo.mobileobservatory.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateDownloadException(message: String) : IOException(message)

class ApkDownloader(
    private val openConnection: (String) -> HttpURLConnection = {
        URL(it).openConnection() as HttpURLConnection
    },
    private val timeoutMillis: Int = 30_000
) {
    /**
     * Streams [url] into [destination] and rejects the file unless its SHA-256
     * matches [expectedSha256]. Progress is reported as a 0..1 fraction.
     */
    fun download(
        url: String,
        destination: File,
        expectedSha256: String,
        onProgress: (Float) -> Unit = {}
    ): File {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, "${destination.name}.part")
        part.delete()

        val connection = openConnection(url).apply {
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", HttpUpdateFeedFetcher.USER_AGENT)
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw UpdateDownloadException("Server returned HTTP ${connection.responseCode}.")
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var lastReportedBucket = -1

            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        if (totalBytes != null) {
                            val bucket = ((copied * 100) / totalBytes).toInt()
                            if (bucket != lastReportedBucket) {
                                lastReportedBucket = bucket
                                onProgress((bucket / 100f).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }

            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                throw UpdateDownloadException("Downloaded package failed checksum verification.")
            }
            if (destination.exists() && !destination.delete()) {
                throw UpdateDownloadException("Could not replace the previous download.")
            }
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
            onProgress(1f)
            return destination
        } catch (error: Throwable) {
            part.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
