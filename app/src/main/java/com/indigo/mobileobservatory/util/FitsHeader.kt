package com.indigo.mobileobservatory.util

import java.io.File
import java.io.RandomAccessFile

/** A parsed FITS header whose reader is positioned at the associated data block. */
data class FitsHeader(
    val cards: Map<String, String>,
    val dataOffset: Long
) {
    operator fun get(key: String): String? = cards[key]

    operator fun contains(key: String): Boolean = cards.containsKey(key)

    fun intValue(key: String): Int? = cards[key]?.toIntOrNull()

    fun longValue(key: String): Long? = cards[key]?.toLongOrNull()

    fun doubleValue(key: String): Double? =
        cards[key]?.replace('D', 'E', ignoreCase = true)?.toDoubleOrNull()
}

/** Shared bounded parser for FITS primary and extension headers. */
object FitsHeaderParser {
    const val BLOCK_SIZE = 2880
    const val CARD_SIZE = 80
    const val DEFAULT_MAX_CARDS = 12_000

    fun read(file: File, maxCards: Int = DEFAULT_MAX_CARDS): FitsHeader =
        RandomAccessFile(file, "r").use { read(it, maxCards) }

    /** Reads from the current HDU and advances [reader] to the first byte of its data block. */
    fun read(reader: RandomAccessFile, maxCards: Int = DEFAULT_MAX_CARDS): FitsHeader {
        require(maxCards > 0) { "FITS header card limit must be positive" }
        val cards = linkedMapOf<String, String>()
        val bytes = ByteArray(CARD_SIZE)
        repeat(maxCards) {
            require(reader.length() - reader.filePointer >= CARD_SIZE) { "Truncated FITS header" }
            reader.readFully(bytes)
            val card = String(bytes, Charsets.US_ASCII)
            val key = card.substring(0, 8).trim()
            if (key == "END") {
                val dataOffset = alignedBlockOffset(reader.filePointer)
                require(dataOffset <= reader.length()) { "Truncated FITS header block" }
                reader.seek(dataOffset)
                return FitsHeader(cards.toMap(), dataOffset)
            }
            if (key.isNotEmpty() && card.getOrNull(8) == '=') {
                cards[key] = parseValue(card.substring(10))
            }
        }
        error("FITS header exceeds safe card limit")
    }

    private fun alignedBlockOffset(offset: Long): Long =
        Math.multiplyExact(Math.addExact(offset, BLOCK_SIZE - 1L) / BLOCK_SIZE, BLOCK_SIZE.toLong())

    /** FITS comments start at `/` only outside quoted string values. */
    private fun parseValue(field: String): String {
        val value = field.trimStart()
        if (!value.startsWith('\'')) return value.substringBefore('/').trim()

        val parsed = StringBuilder()
        var index = 1
        while (index < value.length) {
            val character = value[index]
            if (character == '\'') {
                if (value.getOrNull(index + 1) == '\'') {
                    parsed.append('\'')
                    index += 2
                    continue
                }
                return parsed.toString().trimEnd()
            }
            parsed.append(character)
            index++
        }
        return parsed.toString().trimEnd()
    }
}
