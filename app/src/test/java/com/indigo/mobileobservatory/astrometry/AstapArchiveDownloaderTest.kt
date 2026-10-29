package com.indigo.mobileobservatory.astrometry

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
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
    private lateinit var server: HttpServer
    private val original = "original star database archive".toByteArray()
    private val corrupt = "corrupt! star database archive".toByteArray()
    private val hits = AtomicInteger()
    private lateinit var destination: File
    private val sha256: String get() = MessageDigest.getInstance("SHA-256")
        .digest(original).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Before fun setup() {
        assertEquals(original.size, corrupt.size)
        destination = temp.newFile("archive.zip")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.createContext("/corrupt") { exchange ->
            exchange.sendResponseHeaders(200, corrupt.size.toLong())
            exchange.responseBody.use { it.write(corrupt) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/valid")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/valid") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(200, original.size.toLong())
            exchange.responseBody.use { it.write(original) }
        }
        server.start()
    }

    @After fun cleanup() { server.stop(0) }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}/$path"

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
