package com.indigo.mobileobservatory.astrometry

import java.io.File
import java.nio.charset.StandardCharsets

data class FitsSolveHints(
    val width: Int = 0,
    val height: Int = 0,
    val pixelSizeUm: Double? = null,
    val focalLengthMm: Double? = null,
    val binning: Int = 1,
    val fovHeightDeg: Double? = null
)

object FitsSolveHintReader {
    fun read(file: File): FitsSolveHints {
        if (!file.extension.equals("fit", true) && !file.extension.equals("fits", true)) {
            return FitsSolveHints()
        }
        val header = readHeader(file)
        val width = header.intValue("NAXIS1") ?: 0
        val height = header.intValue("NAXIS2") ?: 0
        val binning = header.intValue("YBINNING") ?: header.intValue("XBINNING") ?: 1
        val pixelSize = header.doubleValue("YPIXSZ") ?: header.doubleValue("XPIXSZ")
        val focal = header.doubleValue("FOCALLEN")
        val fovHeight = header.doubleValue("FOVH")
            ?: header.doubleValue("PIXSCALE")?.let { it * height / 3600.0 }
            ?: if (pixelSize != null && focal != null && focal > 0.0 && height > 0) {
                206.265 * pixelSize * binning.coerceAtLeast(1) / focal * height / 3600.0
            } else {
                null
            }
        return FitsSolveHints(width, height, pixelSize, focal, binning.coerceAtLeast(1), fovHeight)
    }

    private fun readHeader(file: File): Map<String, String> {
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(2880 * 64)
            val count = input.read(buffer)
            if (count <= 0) return emptyMap()
            buffer.copyOf(count)
        }
        val cards = mutableMapOf<String, String>()
        var offset = 0
        while (offset + 80 <= bytes.size) {
            val card = String(bytes, offset, 80, StandardCharsets.US_ASCII)
            offset += 80
            val key = card.take(8).trim()
            if (key == "END") break
            val eq = card.indexOf('=')
            if (key.isNotEmpty() && eq >= 0) {
                cards[key] = card.substring(eq + 1).substringBefore('/').trim().trim('\'')
            }
        }
        return cards
    }

    private fun Map<String, String>.doubleValue(key: String): Double? {
        return this[key]?.replace("D", "E", ignoreCase = true)?.toDoubleOrNull()
    }

    private fun Map<String, String>.intValue(key: String): Int? {
        return this[key]?.toIntOrNull()
    }
}
