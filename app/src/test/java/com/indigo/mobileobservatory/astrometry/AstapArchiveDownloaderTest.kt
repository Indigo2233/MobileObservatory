package com.indigo.mobileobservatory.astrometry

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AstapArchiveDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private val original = "original star database archive".toByteArray()
    private val corrupt = "corrupt! star database archive".toByteArray()
    private val hits = AtomicInteger()
    private lateinit var destination: File
    private val sha256: String get() = MessageDigest.getInstance("SHA-256")
        .digest(original).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Before fun setup() {
        assertEquals(original.size, corrupt.size)
        destination = temp.newFile("archive.zip")
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverThread = thread(isDaemon = true, name = "astap-http-test") {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val path = reader.readLine().split(" ")[1]
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val (status, body, extra) = when (path) {
                        "/missing" -> Triple("404 Not Found", byteArrayOf(), "")
                        "/corrupt" -> Triple("200 OK", corrupt, "")
                        "/redirect" -> Triple("302 Found", byteArrayOf(), "Location: /valid\r\n")
                        "/valid" -> { hits.incrementAndGet(); Triple("200 OK", original, "") }
                        else -> Triple("404 Not Found", byteArrayOf(), "")
                    }
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(body)
                    output.flush()
                }
            } catch (e: SocketException) {
                if (!server.isClosed) throw e
            }
        }
    }

    @After fun cleanup() { server.close(); serverThread.join(5000) }
    private fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path"

    @Test fun httpAndDigestFailuresFallBackToACompleteValidArchive() = runBlocking {
        val updates = mutableListOf<DownloadProgress>()
        AstapArchiveDownloader.download(
            listOf(url("missing"), url("corrupt"), url("valid")), destination,
            original.size.toLong(), sha256, "D20", updates::add
        )
        assertArrayEquals(original, destination.readBytes())
        assertEquals(1, hits.get())
        assertEquals(original.size.toLong(), updates.last().bytesRead)
        assertEquals(0L, updates.first().bytesRead)
        assertEquals("Downloading D20 (backup source)", updates.last().message)
    }

    @Test fun allFailuresDeleteThePartialArchive() {
        assertThrows(IOException::class.java) {
            runBlocking {
                AstapArchiveDownloader.download(
                    listOf(url("corrupt"), url("missing")), destination,
                    original.size.toLong(), sha256, "D20"
                ) { }
            }
        }
        assertFalse(destination.exists())
    }

    @Test fun followsTheDownloadRedirect() = runBlocking {
        val updates = mutableListOf<DownloadProgress>()
        AstapArchiveDownloader.download(
            listOf(url("redirect")), destination, original.size.toLong(), sha256, "D20"
        ) { updates.add(it) }
        assertArrayEquals(original, destination.readBytes())
        assertEquals(1, hits.get())
        assertEquals(updates.map { it.bytesRead }.sorted(), updates.map { it.bytesRead })
    }

    @Test fun cancellationDoesNotTryTheNextSource() = runBlocking {
        val job = launch(start = CoroutineStart.LAZY) {
            AstapArchiveDownloader.download(
                listOf(url("valid"), url("valid")), destination,
                original.size.toLong(), sha256, "D20"
            ) { if (it.bytesRead > 0) coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
        job.start()
        job.join()
        assertEquals(1, hits.get())
    }
}
