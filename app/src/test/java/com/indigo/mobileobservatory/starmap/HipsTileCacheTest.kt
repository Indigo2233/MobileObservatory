package com.indigo.mobileobservatory.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class HipsTileCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun resolveCacheFileMapsSurveyRelativePath() {
        val root = tmp.newFolder("hips-cache")
        val cache = cache(root, online = true)
        val file = cache.resolveCacheFile("dss", "Norder3/Dir0/Npix42.webp")
        assertEquals(
            File(root, "dss/Norder3/Dir0/Npix42.webp").canonicalFile,
            file!!.canonicalFile
        )
    }

    @Test
    fun whitelistRejectsUnknownSurvey() {
        val root = tmp.newFolder("hips-cache")
        val cache = cache(root, online = true)
        assertNull(cache.resolveCacheFile("milkyway", "Norder0/Npix0.webp"))
        assertNull(cache.resolveCacheFile("unknown", "properties"))
        assertNull(HipsTileCache.parseSurveyPath("evil/Norder0/Npix0.webp"))
        assertNull(HipsTileCache.parseSurveyPath("dss"))
    }

    @Test
    fun rejectsPathTraversal() {
        assertNull(HipsTileCache.normalizeRelativePath("../secrets"))
        assertNull(HipsTileCache.normalizeRelativePath("Norder0/../../etc/passwd"))
        assertNull(HipsTileCache.parseSurveyPath("dss/../other/properties"))
        val root = tmp.newFolder("hips-cache")
        val cache = cache(root, online = true)
        assertNull(cache.resolveCacheFile("dss", "../escape.webp"))
    }

    @Test
    fun getOrFetchDownloadsOnceAndServesCache() {
        val root = tmp.newFolder("hips-cache")
        val url = "https://alasky.cds.unistra.fr/DSS/DSSColor/Norder0/Dir0/Npix0.webp"
        val recording = RecordingFetcher(mapOf(url to byteArrayOf(1, 2, 3)))
        val cache = HipsTileCache(root, recording, isOnline = { true })
        val a = cache.getOrFetch("dss", "Norder0/Dir0/Npix0.webp")
        val b = cache.getOrFetch("dss", "Norder0/Dir0/Npix0.webp")
        assertNotNull(a)
        assertEquals(a!!.canonicalFile, b!!.canonicalFile)
        assertEquals(listOf(url), recording.urls)
        assertEquals(3, a.length())
    }

    @Test
    fun offlineMissDoesNotFetch() {
        val root = tmp.newFolder("hips-cache")
        val recording = RecordingFetcher(emptyMap())
        val cache = HipsTileCache(root, recording, isOnline = { false })
        assertNull(cache.getOrFetch("dss", "properties"))
        assertTrue(recording.urls.isEmpty())
    }

    @Test
    fun lruEvictsOldestTilesButKeepsProperties() {
        val root = tmp.newFolder("hips-cache")
        val cache = HipsTileCache(
            cacheRoot = root,
            fetcher = { error("no network") },
            isOnline = { false },
            maxBytes = 30
        )
        val props = File(root, "dss/properties").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10) { 1 })
            setLastModified(1_000L)
        }
        val oldTile = File(root, "dss/Norder1/Dir0/Npix1.webp").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(20) { 2 })
            setLastModified(2_000L)
        }
        val newTile = File(root, "dss/Norder2/Dir0/Npix2.webp").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(20) { 3 })
            setLastModified(3_000L)
        }
        cache.enforceLimit()
        assertTrue(props.isFile)
        assertFalse(oldTile.exists())
        assertTrue(newTile.isFile)
        assertTrue(cache.cacheSizeBytes() <= 30)
    }

    @Test
    fun clearCacheRemovesAllFiles() {
        val root = tmp.newFolder("hips-cache")
        File(root, "dss/Norder0/Npix0.webp").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        val cache = cache(root, online = false)
        assertTrue(cache.cacheSizeBytes() > 0)
        cache.clearCache()
        assertEquals(0L, cache.cacheSizeBytes())
        assertTrue(root.isDirectory)
    }

    @Test
    fun pathHandlerSwallowsFetcherFailures() {
        // A throw here is re-thrown by Chromium on the UI thread and kills the app.
        val root = tmp.newFolder("hips-cache")
        val cache = HipsTileCache(
            cacheRoot = root,
            fetcher = { throw SecurityException("no ACCESS_NETWORK_STATE") },
            isOnline = { throw SecurityException("no ACCESS_NETWORK_STATE") }
        )
        val handler = cache.pathHandler()
        assertNotNull(handler.handle("dss/Norder3/Dir0/Npix100.webp"))
        assertNotNull(handler.handle("unknown/properties"))
        assertNotNull(handler.handle("dss/../escape"))
    }

    @Test
    fun mimeTypesMatchExtension() {
        assertEquals("image/webp", HipsTileCache.mimeTypeFor("Norder0/Npix0.webp"))
        assertEquals("image/jpeg", HipsTileCache.mimeTypeFor("a/b.jpg"))
        assertEquals("text/plain", HipsTileCache.mimeTypeFor("properties"))
    }

    private fun cache(root: File, online: Boolean): HipsTileCache =
        HipsTileCache(root, RecordingFetcher(emptyMap()), isOnline = { online })

    private class RecordingFetcher(
        private val payloads: Map<String, ByteArray>
    ) : HipsTileFetcher {
        val urls = mutableListOf<String>()

        override fun fetch(url: String): ByteArray? {
            urls += url
            return payloads[url] ?: throw IOException("missing $url")
        }
    }
}
