package com.indigo.mobileobservatory.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkDownloaderTest {
    private val payload = "Indigo Observatory update payload".toByteArray()
    private val payloadSha256 = sha256(payload)

    @Test
    fun `writes the file and reports progress when the checksum matches`() {
        val directory = Files.createTempDirectory("update-download").toFile()
        val destination = File(directory, "IndigoObservatory.apk")
        val progress = mutableListOf<Float>()
        val downloader = ApkDownloader(openConnection = { FakeConnection(payload) })

        val result = downloader.download(
            url = "https://example.com/app.apk",
            destination = destination,
            expectedSha256 = payloadSha256,
            onProgress = { progress += it }
        )

        assertEquals(destination, result)
        assertTrue(destination.isFile)
        assertTrue(payload.contentEquals(destination.readBytes()))
        assertEquals(1f, progress.last())
    }

    @Test
    fun `rejects and removes the file when the checksum does not match`() {
        val directory = Files.createTempDirectory("update-download").toFile()
        val destination = File(directory, "IndigoObservatory.apk")
        val downloader = ApkDownloader(openConnection = { FakeConnection(payload) })

        assertThrows(UpdateDownloadException::class.java) {
            downloader.download(
                url = "https://example.com/app.apk",
                destination = destination,
                expectedSha256 = "b".repeat(64)
            )
        }

        assertFalse(destination.exists())
        assertFalse(File(directory, "IndigoObservatory.apk.part").exists())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FakeConnection(private val body: ByteArray) :
        HttpURLConnection(URL("https://example.com/app.apk")) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = HTTP_OK

        override fun getContentLengthLong(): Long = body.size.toLong()

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }
}
