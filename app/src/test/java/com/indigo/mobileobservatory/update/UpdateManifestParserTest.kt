package com.indigo.mobileobservatory.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestParserTest {
    private val sha256 = "a".repeat(64)

    private fun manifestJson(
        versionCode: Int = 43,
        versionName: String = "1.0.7",
        apkUrl: String = "https://example.com/app.apk",
        sha256: String = this.sha256,
        mandatory: Boolean = false,
        minSupportedVersionCode: Int = 0
    ): String = """
        {
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "apkUrl": "$apkUrl",
          "sha256": "$sha256",
          "notes": "Fixes for the mount reconnect path.",
          "mandatory": $mandatory,
          "minSupportedVersionCode": $minSupportedVersionCode
        }
    """.trimIndent()

    @Test
    fun `parses a complete manifest`() {
        val manifest = UpdateManifestParser.parse(manifestJson())

        assertEquals(43, manifest.versionCode)
        assertEquals("1.0.7", manifest.versionName)
        assertEquals("https://example.com/app.apk", manifest.apkUrl)
        assertEquals(sha256, manifest.sha256)
        assertEquals("Fixes for the mount reconnect path.", manifest.notes)
        assertFalse(manifest.mandatory)
    }

    @Test
    fun `rejects a manifest without a checksum`() {
        assertThrows(UpdateManifestFormatException::class.java) {
            UpdateManifestParser.parse(manifestJson(sha256 = ""))
        }
    }

    @Test
    fun `rejects a non-https download url`() {
        assertThrows(UpdateManifestFormatException::class.java) {
            UpdateManifestParser.parse(manifestJson(apkUrl = "http://example.com/app.apk"))
        }
    }

    @Test
    fun `rejects a manifest without a version code`() {
        assertThrows(UpdateManifestFormatException::class.java) {
            UpdateManifestParser.parse(manifestJson(versionCode = 0))
        }
    }

    @Test
    fun `compares version codes against the running build`() {
        val manifest = UpdateManifestParser.parse(manifestJson(versionCode = 43))

        assertTrue(manifest.isNewerThan(42))
        assertFalse(manifest.isNewerThan(43))
        assertFalse(manifest.isNewerThan(44))
    }

    @Test
    fun `treats an outdated minimum version as required`() {
        val manifest = UpdateManifestParser.parse(
            manifestJson(versionCode = 50, minSupportedVersionCode = 45)
        )

        assertTrue(manifest.isRequiredFor(44))
        assertFalse(manifest.isRequiredFor(45))
    }

    @Test
    fun `treats an explicitly mandatory release as required`() {
        val manifest = UpdateManifestParser.parse(manifestJson(mandatory = true))

        assertTrue(manifest.isRequiredFor(42))
    }

    @Test
    fun `feed falls back to the mirror when the release asset fails`() {
        val requested = mutableListOf<String>()
        val feed = UpdateFeed { url ->
            requested += url
            if (url.contains("releases")) null else manifestJson()
        }

        val manifest = feed.latest(listOf("https://github.com/releases/update.json", "https://mirror/update.json"))

        assertEquals(43, manifest?.versionCode)
        assertEquals(2, requested.size)
    }

    @Test
    fun `feed skips a malformed source and keeps looking`() {
        val feed = UpdateFeed { url ->
            if (url.contains("releases")) "{ not json" else manifestJson()
        }

        val manifest = feed.latest(listOf("https://github.com/releases/update.json", "https://mirror/update.json"))

        assertEquals("1.0.7", manifest?.versionName)
    }

    @Test
    fun `feed returns null when every source is unavailable`() {
        val feed = UpdateFeed { null }

        assertNull(feed.latest(listOf("https://github.com/releases/update.json", "https://mirror/update.json")))
    }
}
