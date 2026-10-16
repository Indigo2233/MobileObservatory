package com.indigo.mobileobservatory.util

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FitsHeaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `parses quoted values comments escaped quotes and numeric types`() {
        val file = folder.newFile("header.fits")
        writeHeader(
            file,
            card("SIMPLE", "T", "standard FITS"),
            card("OBJECT", "'M31 / Andromeda'", "slash belongs to the value"),
            card("OBSERVER", "'O''Brien'", "escaped quote"),
            card("FOCALLEN", "8.0D+02", "Fortran exponent"),
            "END".padEnd(80)
        )

        val header = FitsHeaderParser.read(file)

        assertEquals("T", header["SIMPLE"])
        assertEquals("M31 / Andromeda", header["OBJECT"])
        assertEquals("O'Brien", header["OBSERVER"])
        assertEquals(800.0, header.doubleValue("FOCALLEN")!!, 0.0)
        assertEquals(2880L, header.dataOffset)
    }

    @Test
    fun `reader advances to the aligned data block`() {
        val file = folder.newFile("offset.fits")
        val cards = MutableList(37) { index -> card("K$index", index.toString(), "padding") }
        writeHeader(file, *(cards + "END".padEnd(80)).toTypedArray())

        RandomAccessFile(file, "r").use { reader ->
            val header = FitsHeaderParser.read(reader)
            assertEquals(5760L, header.dataOffset)
            assertEquals(header.dataOffset, reader.filePointer)
        }
    }

    @Test
    fun `reads a later extension header from the current reader position`() {
        val file = folder.newFile("extension.fits")
        val primary = headerBlock(card("SIMPLE", "T", "primary"), "END".padEnd(80))
        val extension = headerBlock(
            card("XTENSION", "'IMAGE'", "extension"),
            card("EXTNAME", "'STACKMETA'", "name"),
            "END".padEnd(80)
        )
        file.writeBytes((primary + extension).toByteArray(Charsets.US_ASCII))

        RandomAccessFile(file, "r").use { reader ->
            assertEquals(2880L, FitsHeaderParser.read(reader).dataOffset)
            val parsedExtension = FitsHeaderParser.read(reader)
            assertEquals("STACKMETA", parsedExtension["EXTNAME"])
            assertEquals(5760L, parsedExtension.dataOffset)
        }
    }

    @Test
    fun `rejects missing end card and truncated header padding`() {
        val missingEnd = folder.newFile("missing-end.fits")
        missingEnd.writeBytes(card("SIMPLE", "T", "missing end").padEnd(2880).toByteArray(Charsets.US_ASCII))
        assertThrows(IllegalArgumentException::class.java) { FitsHeaderParser.read(missingEnd) }

        val truncated = folder.newFile("truncated.fits")
        truncated.writeBytes("END".padEnd(80).toByteArray(Charsets.US_ASCII))
        assertThrows(IllegalArgumentException::class.java) { FitsHeaderParser.read(truncated) }
    }

    private fun writeHeader(file: File, vararg cards: String) {
        file.writeBytes(headerBlock(*cards).toByteArray(Charsets.US_ASCII))
    }

    private fun headerBlock(vararg cards: String): String {
        val text = cards.joinToString("")
        return text.padEnd(((text.length + 2879) / 2880) * 2880, ' ')
    }

    private fun card(key: String, value: String, comment: String): String =
        (key.padEnd(8).take(8) + "= " + value.padStart(20) + " / " + comment).padEnd(80).take(80)
}
