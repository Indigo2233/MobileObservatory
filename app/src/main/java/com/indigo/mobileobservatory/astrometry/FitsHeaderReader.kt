package com.indigo.mobileobservatory.astrometry

import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Reads FITS header cards, plus the sidecar keyword files that some solvers emit. */
object FitsHeaderReader {
    private const val CARD_LENGTH = 80
    private const val MAX_HEADER_BYTES = 2880 * 64

    fun read(file: File): Map<String, String> = when {
        file.extension.equals("fit", true) || file.extension.equals("fits", true) -> readFits(file)
        file.extension.equals("wcs", true) ||
            file.extension.equals("ini", true) ||
            file.extension.equals("txt", true) -> readText(file)
        else -> emptyMap()
    }

    private fun readFits(file: File): Map<String, String> {
        val bytes = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(MAX_HEADER_BYTES)
                val count = input.read(buffer)
                if (count <= 0) null else buffer.copyOf(count)
            }
        }.getOrNull() ?: return emptyMap()

        val cards = mutableMapOf<String, String>()
        var offset = 0
        while (offset + CARD_LENGTH <= bytes.size) {
            val card = String(bytes, offset, CARD_LENGTH, StandardCharsets.US_ASCII)
            offset += CARD_LENGTH
            val key = card.take(8).trim()
            if (key == "END") break
            val separator = card.indexOf('=')
            if (key.isNotEmpty() && separator >= 0) {
                cards[key] = card.substring(separator + 1).substringBefore('/').trim().trim('\'').trim()
            }
        }
        return cards
    }

    private fun readText(file: File): Map<String, String> {
        val cards = mutableMapOf<String, String>()
        runCatching { file.readLines() }.getOrDefault(emptyList()).forEach { line ->
            val key = line.take(8).trim()
            val separator = line.indexOf('=')
            if (key.isNotEmpty() && separator >= 0) {
                cards[key] = line.substring(separator + 1).substringBefore('/').trim().trim('\'').trim()
            }
        }
        return cards
    }

    /**
     * Mid-exposure instant of a frame, which is the epoch the plate solve result actually refers
     * to. Polar alignment needs it because the sky moves 15 arcseconds per second of clock error.
     */
    fun midExposureTime(file: File): Instant? {
        val cards = read(file)
        val start = parseDateObs(cards["DATE-OBS"] ?: cards["DATE_OBS"]) ?: return null
        val exposureSeconds = sequenceOf("EXPOSURE", "EXPTIME", "EXPOSURE_TIME")
            .mapNotNull { cards[it]?.toDoubleOrNull() }
            .firstOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: 0.0
        return start.plusMillis((exposureSeconds * 500.0).toLong())
    }

    private fun parseDateObs(value: String?): Instant? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()
    }
}
