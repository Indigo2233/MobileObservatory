package com.indigo.mobileobservatory.starmap

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * On-demand HiPS tile proxy: cache under [filesDir]/hips-cache, fetch over HTTP
 * on miss. WebView only talks to appassets; this PathHandler is the sole
 * outbound hop.
 */
class HipsTileCache(
    private val cacheRoot: File,
    private val fetcher: HipsTileFetcher,
    private val isOnline: () -> Boolean,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    private val inFlight = ConcurrentHashMap<String, ReentrantLock>()

    fun pathHandler(): WebViewAssetLoader.PathHandler = HipsPathHandler()

    /** Maps `/hips/{surveyId}/{relativePath}` suffix to a cache file, or null if rejected. */
    fun resolveCacheFile(surveyId: String, relativePath: String): File? {
        if (!SURVEY_BASE_URLS.containsKey(surveyId)) return null
        val safe = normalizeRelativePath(relativePath) ?: return null
        return File(File(cacheRoot, surveyId), safe)
    }

    /**
     * Returns a readable cached file, downloading when missing and online.
     * Null means miss / offline / download failure (caller should 404).
     */
    fun getOrFetch(surveyId: String, relativePath: String): File? {
        val file = resolveCacheFile(surveyId, relativePath) ?: return null
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        if (!isOnline()) return null
        val base = SURVEY_BASE_URLS[surveyId] ?: return null
        val safe = normalizeRelativePath(relativePath) ?: return null
        val key = "$surveyId/$safe"
        val lock = inFlight.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        try {
            if (file.isFile && file.length() > 0L) {
                file.setLastModified(System.currentTimeMillis())
                return file
            }
            val url = "$base/$safe"
            val bytes = try {
                fetcher.fetch(url)
            } catch (_: IOException) {
                null
            } ?: return null
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) {
                    file.writeBytes(bytes)
                    tmp.delete()
                }
            } catch (_: IOException) {
                tmp.delete()
                return null
            }
            enforceLimitLocked()
            return file
        } finally {
            lock.unlock()
            if (!lock.hasQueuedThreads()) {
                inFlight.remove(key, lock)
            }
        }
    }

    fun cacheSizeBytes(): Long {
        if (!cacheRoot.isDirectory) return 0L
        return cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clearCache() {
        if (!cacheRoot.exists()) return
        cacheRoot.deleteRecursively()
        cacheRoot.mkdirs()
    }

    /** Drop oldest tile files (not `properties`) until under [maxBytes]. */
    fun enforceLimit() {
        enforceLimitLocked()
    }

    private fun enforceLimitLocked() {
        if (!cacheRoot.isDirectory) return
        var total = cacheSizeBytes()
        if (total <= maxBytes) return
        val tiles = cacheRoot.walkTopDown()
            .filter { it.isFile && !isProtectedCacheFile(it) }
            .sortedBy { it.lastModified() }
            .toList()
        for (tile in tiles) {
            if (total <= maxBytes) break
            val len = tile.length()
            if (tile.delete()) total -= len
        }
    }

    private inner class HipsPathHandler : WebViewAssetLoader.PathHandler {
        /**
         * Anything thrown here is re-thrown by Chromium on the UI thread and
         * kills the process, so every failure has to come back as a 404 — the
         * engine simply falls back to a lower-order tile.
         */
        override fun handle(path: String): WebResourceResponse = try {
            val parsed = parseSurveyPath(path)
            val file = parsed?.let { (surveyId, relative) -> getOrFetch(surveyId, relative) }
            if (file == null) {
                notFound()
            } else {
                WebResourceResponse(
                    mimeTypeFor(parsed.second),
                    /* encoding = */ null,
                    FileInputStream(file)
                )
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "HiPS tile request failed: $path", throwable)
            notFound()
        }
    }

    companion object {
        private const val TAG = "HipsTileCache"
        const val DEFAULT_MAX_BYTES: Long = 500L * 1024L * 1024L
        const val CACHE_DIR_NAME = "hips-cache"
        const val PREFS_ONLINE_DSS = "star_map_online_dss"

        /** Whitelist: survey id → remote HiPS root (no trailing slash). */
        val SURVEY_BASE_URLS: Map<String, String> = mapOf(
            "dss" to "https://alasky.cds.unistra.fr/DSS/DSSColor"
        )

        fun create(context: Context, maxBytes: Long = DEFAULT_MAX_BYTES): HipsTileCache {
            val root = File(context.filesDir, CACHE_DIR_NAME)
            root.mkdirs()
            val appContext = context.applicationContext
            return HipsTileCache(
                cacheRoot = root,
                fetcher = HttpHipsTileFetcher(),
                isOnline = { isNetworkAvailable(appContext) },
                maxBytes = maxBytes
            )
        }

        /** Optimistic on failure: let the download itself decide, never block on a probe. */
        fun isNetworkAvailable(context: Context): Boolean = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        } catch (throwable: Throwable) {
            Log.w(TAG, "Network state unavailable", throwable)
            true
        }

        fun parseSurveyPath(path: String): Pair<String, String>? {
            val trimmed = path.trim().trimStart('/')
            if (trimmed.isEmpty()) return null
            val slash = trimmed.indexOf('/')
            if (slash <= 0 || slash >= trimmed.length - 1) return null
            val surveyId = trimmed.substring(0, slash)
            val relative = trimmed.substring(slash + 1)
            if (!SURVEY_BASE_URLS.containsKey(surveyId)) return null
            if (normalizeRelativePath(relative) == null) return null
            return surveyId to relative
        }

        fun normalizeRelativePath(relativePath: String): String? {
            if (relativePath.isBlank()) return null
            val parts = relativePath.replace('\\', '/').split('/')
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            if (parts.any { it == "." || it == ".." }) return null
            if (parts.any { it.contains(':') }) return null
            return parts.joinToString("/")
        }

        fun mimeTypeFor(relativePath: String): String {
            val name = relativePath.substringAfterLast('/').lowercase(Locale.US)
            return when {
                name.endsWith(".webp") -> "image/webp"
                name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
                name.endsWith(".png") -> "image/png"
                name == "properties" || name.endsWith(".txt") -> "text/plain"
                else -> "application/octet-stream"
            }
        }

        fun formatCacheSize(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            return String.format(Locale.US, "%.1f MB", mb)
        }

        private fun isProtectedCacheFile(file: File): Boolean {
            val name = file.name.lowercase(Locale.US)
            return name == "properties" || name.endsWith(".txt")
        }

        /** Explicit 404 with an empty body; a null stream is not portable across WebView builds. */
        private fun notFound(): WebResourceResponse =
            WebResourceResponse(
                "text/plain",
                "utf-8",
                404,
                "Not Found",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
    }
}

fun interface HipsTileFetcher {
    @Throws(IOException::class)
    fun fetch(url: String): ByteArray?
}

class HttpHipsTileFetcher(
    private val timeoutMillis: Int = 10_000
) : HipsTileFetcher {
    override fun fetch(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "IndigoObservatory/1.0")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
