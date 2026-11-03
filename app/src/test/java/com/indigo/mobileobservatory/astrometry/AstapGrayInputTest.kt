package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.FITSWriter
import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AstapGrayInputTest {
    @Test fun `flat RGGB becomes single channel grayscale at source dimensions`() {
        val width = 8
        val height = 6
        val raw = ByteArray(width * height * 2)
        for (y in 0 until height) for (x in 0 until width) {
            val value = when {
                x % 2 == 0 && y % 2 == 0 -> 200
                x % 2 == 1 && y % 2 == 1 -> 50
                else -> 100
            }
            val index = (y * width + x) * 2
            raw[index] = value.toByte()
            raw[index + 1] = (value shr 8).toByte()
        }
        withInput(raw, width, height, PixelFormat.BAYER_RG16) { source ->
            val output = Files.createTempFile("astap-gray-flat", ".fits").toFile().apply { delete() }
            try {
                requireNotNull(AstapGrayInput.inspect(source)).write(output)
                val header = FitsHeaderParser.read(output)
                assertEquals(16, header.intValue("BITPIX"))
                assertEquals(2, header.intValue("NAXIS"))
                assertEquals(width, header.intValue("NAXIS1"))
                assertEquals(height, header.intValue("NAXIS2"))
                assertEquals("1", header["BSCALE"])
                assertEquals("32768", header["BZERO"])
                assertEquals("BILINEAR", header["DEBAYER"])
                assertNull(header["BAYERPAT"])
                val payload = output.readBytes().copyOfRange(
                    header.dataOffset.toInt(), header.dataOffset.toInt() + width * height * 2)
                for (y in 1 until height - 1) for (x in 1 until width - 1) {
                    val i = y * width + x
                    val signed = read16(payload, i * 2)
                    assertEquals(124, signed)
                }
            } finally {
                output.delete()
            }
        }
    }

    @Test fun `all four CFA phases are recognized`() {
        val patterns = mapOf(
            PixelFormat.BAYER_RG16 to "RGGB",
            PixelFormat.BAYER_GR16 to "GRBG",
            PixelFormat.BAYER_GB16 to "GBRG",
            PixelFormat.BAYER_BG16 to "BGGR"
        )
        for ((format, expected) in patterns) {
            withInput(ByteArray(8 * 6 * 2), 8, 6, format) { source ->
                assertEquals(expected, requireNotNull(AstapGrayInput.inspect(source)).bayerPattern)
            }
        }
    }

    @Test fun `mono and malformed files stay on the original solver path`() {
        withInput(ByteArray(8 * 6), 8, 6, PixelFormat.MONO8) { source ->
            assertNull(AstapGrayInput.inspect(source))
        }
        val malformed = Files.createTempFile("astap-gray-invalid", ".fits").toFile()
        try {
            malformed.writeText("not fits")
            assertNull(AstapGrayInput.inspect(malformed))
        } finally {
            malformed.delete()
        }
    }

    @Test fun `odd dimensions are supported`() {
        withInput(ByteArray(5 * 3 * 2), 5, 3, PixelFormat.BAYER_RG16) { source ->
            val output = Files.createTempFile("astap-gray-odd", ".fits").toFile().apply { delete() }
            try {
                requireNotNull(AstapGrayInput.inspect(source)).write(output)
                assertEquals(5, FitsHeaderParser.read(output).intValue("NAXIS1"))
            } finally {
                output.delete()
            }
        }
    }

    @Test fun `cancellation leaves no partial solver input`() {
        withInput(ByteArray(8 * 6 * 2), 8, 6, PixelFormat.BAYER_RG16) { source ->
            val output = Files.createTempFile("astap-gray-cancel", ".fits").toFile().apply { delete() }
            try {
                requireNotNull(AstapGrayInput.inspect(source)).write(output) {
                    throw CancellationException("stop")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                assertFalse(output.exists())
            }
        }
    }

    @Test fun `sixteen bit inputs preserve source values away from image borders`() {
        val width = 6
        val height = 6
        val samples = IntArray(width * height) { 4000 }
        val raw = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, value ->
            raw[i * 2] = value.toByte()
            raw[i * 2 + 1] = (value shr 8).toByte()
        }
        withInput(raw, width, height, PixelFormat.BAYER_RG16) { source ->
            val output = Files.createTempFile("astap-gray-range", ".fits").toFile().apply { delete() }
            try {
                requireNotNull(AstapGrayInput.inspect(source)).write(output)
                val header = FitsHeaderParser.read(output)
                val payload = output.readBytes()
                for (y in 1 until height - 1) for (x in 1 until width - 1) {
                    val i = y * width + x
                    val signed = read16(payload, header.dataOffset.toInt() + i * 2)
                    assertEquals(samples[i], signed)
                }
            } finally {
                output.delete()
            }
        }
    }

    @Test fun `grayscale payload dimensions are original size not RGB triplets`() {
        val width = 8
        val height = 6
        withInput(ByteArray(width * height * 2) { 32 }, width, height, PixelFormat.BAYER_RG16) { source ->
            val output = Files.createTempFile("astap-gray-size", ".fits").toFile().apply { delete() }
            try {
                requireNotNull(AstapGrayInput.inspect(source)).write(output)
                val header = FitsHeaderParser.read(output)
                assertEquals(2, header.intValue("NAXIS"))
                assertTrue(width * height * 2 <= output.length() - header.dataOffset)
            } finally {
                output.delete()
            }
        }
    }

    @Test fun `RGGB and BGGR use mirrored CFA positions`() {
        val width = 4
        val height = 4
        val values = IntArray(width * height) { (it + 1) * 4096 }
        val raw = ByteArray(values.size * 2)
        values.forEachIndexed { i, value ->
            raw[i * 2] = value.toByte()
            raw[i * 2 + 1] = (value shr 8).toByte()
        }
        val results = mutableListOf<File>()
        try {
            for (format in listOf(PixelFormat.BAYER_RG16, PixelFormat.BAYER_BG16)) {
                withInput(raw, width, height, format) { source ->
                    val output = Files.createTempFile("astap-gray-phase", ".fits").toFile().apply { delete() }
                    results += output
                    requireNotNull(AstapGrayInput.inspect(source)).write(output)
                }
            }
            val first = FitsHeaderParser.read(results[0])
            val second = FitsHeaderParser.read(results[1])
            val a = results[0].readBytes().copyOfRange(first.dataOffset.toInt(), first.dataOffset.toInt() + width * height * 2)
            val b = results[1].readBytes().copyOfRange(second.dataOffset.toInt(), second.dataOffset.toInt() + width * height * 2)
            assertFalse(a.contentEquals(b))
        } finally {
            results.forEach { it.delete() }
        }
    }

    @Test fun `real field converts to full resolution single channel input`() {
        val compressed = requireNotNull(javaClass.getResourceAsStream("/astrometry/weak-cfa.fits.gz"))
        val source = Files.createTempFile("astap-gray-real", ".fits").toFile()
        val output = Files.createTempFile("astap-gray-real-output", ".fits").toFile().apply { delete() }
        try {
            java.util.zip.GZIPInputStream(compressed).use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val spec = requireNotNull(AstapGrayInput.inspect(source))
            spec.write(output)
            val header = FitsHeaderParser.read(output)
            assertEquals(spec.width, header.intValue("NAXIS1"))
            assertEquals(spec.height, header.intValue("NAXIS2"))
            assertEquals(2, header.intValue("NAXIS"))
            assertNull(header["BAYERPAT"])
            assertEquals("BILINEAR", header["DEBAYER"])
            assertEquals(header.dataOffset + ((spec.width.toLong() * spec.height * 2 + 2879) / 2880) * 2880,
                output.length())
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun withInput(data: ByteArray, width: Int, height: Int, format: PixelFormat,
        block: (java.io.File) -> Unit) {
        val file = Files.createTempFile("astap-gray-source", ".fits").toFile()
        try {
            FITSWriter().write(file, FrameData(data, width, height, format, 1, 0), 1f, 0f)
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun read16(bytes: ByteArray, offset: Int): Int {
        val signed = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        return signed.toShort().toInt() + 32768
    }
}
