package com.indigo.mobileobservatory.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

fun interface UpdateFeedFetcher {
    /** Returns the response body, or null when the resource is unavailable. */
    @Throws(IOException::class)
    fun fetch(url: String): String?
}

/**
 * Reads the newest [UpdateManifest] from an ordered list of feed locations.
 * The release asset is queried before the GitHub Pages mirror so a stale
 * mirror cannot mask a fresh release.
 */
class UpdateFeed(
    private val fetcher: UpdateFeedFetcher = HttpUpdateFeedFetcher()
) {
    fun latest(urls: List<String>): UpdateManifest? {
        for (url in urls.map { it.trim() }.filter { it.isNotEmpty() }) {
            val body = try {
                fetcher.fetch(url)
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            if (body.isNullOrBlank()) continue
            val manifest = try {
                UpdateManifestParser.parse(body)
            } catch (_: UpdateManifestFormatException) {
                continue
            }
            return manifest
        }
        return null
    }
}

class HttpUpdateFeedFetcher(
    private val timeoutMillis: Int = 10_000
) : UpdateFeedFetcher {
    override fun fetch(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val USER_AGENT = "IndigoObservatory-Android"
    }
}
