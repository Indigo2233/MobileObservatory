package com.indigo.mobileobservatory.stacking

import java.io.RandomAccessFile
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StackFitsWriterTest {
    @Test fun exportPermanentFixtureForIndependentPythonVerification() {
        val directory = File("build/reports/stacking").apply { mkdirs() }
        val file = File(directory, "precision-rgb.fits")
        if (file.exists()) check(file.delete())
        val image = LinearImage(3, 2, 3, floatArrayOf(
            -0.01f, 0f, 1f / 65535f, 2f / 65535f, .1f, .2f,
            .3f, .4f, .5f, .6f, .7f, .8f, .9f, 1f, 1.2f, .001f, .002f, .003f))
        StackFitsWriter.write(file, image, mapOf("source" to "独立验证.ser", "frameCount" to "19",
            "exposureSeconds" to "9.5", "calibrated" to "false", "processing" to "{\"curve\":1,\"mode\":\"linear\"}"))
        assertEquals(0L, file.length() % 2880)
    }

    /** Deliberately independent of writer internals: scan FITS cards and read IEEE values. */
    private fun header(reader: RandomAccessFile): Map<String, String> {
        val cards = mutableMapOf<String, String>()
        while (true) {
            val bytes = ByteArray(80); reader.readFully(bytes)
            val text = String(bytes, Charsets.US_ASCII)
            val key = text.substring(0, 8).trim()
            if (key == "END") break
            cards[key] = text.substring(10).trim().trim('\'').trim()
        }
        reader.seek(((reader.filePointer + 2879) / 2880) * 2880)
        return cards
    }

    @Test fun monoAndRgbReadIndependentlyWithPlanarOrderAndFullMetadata() {
        val directory = Files.createTempDirectory("stack-fits").toFile()
        try {
            for (channels in listOf(1, 3)) {
                val image = LinearImage(3, 2, channels, FloatArray(6 * channels) { (it - 2) / 65535f })
                val metadata = mapOf("source" to "相机源 'one'\nSER", "frameCount" to "17", "exposureSeconds" to "12.3456789",
                    "calibrated" to "false", "processing" to "{\"black\":0.0001,\"curve\":5.125}", "long" to "x".repeat(300))
                val file = directory.resolve("result-$channels.fits")
                StackFitsWriter.write(file, image, metadata)
                val reopened = StackFitsReader.read(file)
                assertArrayEquals(image.pixels, reopened.pixels, 0f)
                assertEquals(channels, reopened.channels)
                RandomAccessFile(file, "r").use { reader ->
                    val h = header(reader)
                    assertEquals("-32", h["BITPIX"])
                    assertEquals(if (channels == 3) "3" else "2", h["NAXIS"])
                    assertEquals("17", h["NCOMBINE"])
                    assertEquals("12.3456789", h["EXPTIME"])
                    assertNull(h["BZERO"])
                    for (c in 0 until channels) for (i in 0 until 6) {
                        assertEquals(image.pixels[i * channels + c].toRawBits(), reader.readInt())
                    }
                    reader.seek(((reader.filePointer + 2879) / 2880) * 2880)
                    val extension = header(reader)
                    assertEquals("STACKMETA", extension["EXTNAME"])
                    val json = ByteArray(extension.getValue("NAXIS1").toInt()); reader.readFully(json)
                    val parsed = JSONObject(String(json, Charsets.UTF_8))
                    for ((key, value) in metadata) assertEquals(value, parsed.getString(key))
                    assertEquals(0L, file.length() % 2880)
                }
            }
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun cancelledAndFailedExportsLeaveNoPartialFileAndNeverOverwrite() {
        val directory = Files.createTempDirectory("stack-fits-failure").toFile()
        try {
            val image = LinearImage(2, 2, 1, FloatArray(4) { .5f })
            val file = directory.resolve("output.fits")
            assertThrows(InterruptedException::class.java) { StackFitsWriter.write(file, image) { throw InterruptedException() } }
            assertFalse(file.exists())
            assertEquals(0, directory.listFiles()!!.size)
            val bad = image.copy(pixels = floatArrayOf(0f, Float.NaN, 0f, 0f))
            assertThrows(IllegalArgumentException::class.java) { StackFitsWriter.write(file, bad) }
            assertEquals(0, directory.listFiles()!!.size)
            file.writeText("source data")
            assertThrows(IllegalArgumentException::class.java) { StackFitsWriter.write(file, image) }
            assertEquals("source data", file.readText())
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
