package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.stacking.StackFitsReader
import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MediaImageEditingTest {
    @Test fun trueColorFitsPreservesSubEightBitPrecisionAndPlanarOrder() = directory { root ->
        val values = intArrayOf(32768, 32769, 6560, 1311, 26214, 59637, 8520, 17694, 53083, 32770, 32771, 1).map(Int::toShort).toShortArray()
        val image = MediaImagePixels(2, 2, 3, values)
        val output = File(root, "edited.fit")
        val metadata = mapOf("schema" to "indigo-image-edit-1", "source" to "原图'相机.fit",
            "fits.BAYERPAT" to "RGGB", "fits.DATE-OBS" to "2026-10-22T01:02:03Z",
            "fits.CRPIX1" to "1.5", "fits.CD1_1" to "-1.0D-4", "fits.CTYPE1" to "RA---TAN-SIP",
            "fits.A_ORDER" to "2", "fits.A_2_0" to "0.00002", "gamma" to "1.25")
        MediaImageFitsCodec.write(output, image, metadata)
        val header = FitsHeaderParser.read(output)
        assertEquals("3", header["NAXIS"]); assertEquals("3", header["NAXIS3"])
        assertEquals("RGB", header["COLORSPC"]); assertEquals("display intensity", header["BUNIT"])
        assertEquals("16", header["BITPIX"]); assertEquals("32768", header["BZERO"])
        assertNull(header["BAYERPAT"]); assertEquals("1.5", header["CRPIX1"])
        assertEquals("RA---TAN-SIP", header["CTYPE1"]); assertEquals("2", header["A_ORDER"])
        assertEquals("0.00002", header["A_2_0"])
        assertFalse(StackFitsReader.isNormalizedStack(output))
        assertEquals(metadata, StackFitsReader.readMetadata(output))
        RandomAccessFile(output, "r").use { input ->
            input.seek(header.dataOffset)
            for (c in 0..2) for (pixel in 0..3) assertEquals((values[pixel * 3 + c].toInt() and 65535) - 32768, input.readShort().toInt())
        }
        val reopened = MediaImageFitsCodec.read(output)
        assertTrue(reopened.displayNormalized)
        assertArrayEquals(values, reopened.image.pixels)
        assertEquals(0L, output.length() % 2880)
    }

    @Test fun unsignedBayerInputsBecomeFullResolutionRgbAcrossAllPatternsAndOffsets() = directory { root ->
        for (pattern in listOf("RGGB", "GRBG", "GBRG", "BGGR")) for (offset in 0..1) {
            val file = File(root, "$pattern-$offset.fit")
            val redX = ((if (pattern == "GRBG" || pattern == "BGGR") 1 else 0) - offset) and 1
            val redY = if (pattern == "GBRG" || pattern == "BGGR") 1 else 0
            primary(file, 7, 5, 16, mapOf("BZERO" to "32768", "BAYERPAT" to "'$pattern'", "XBAYROFF" to "$offset")) { out ->
                for (y in 0..4) for (x in 0..6) {
                    val sample = when { (x and 1) == redX && (y and 1) == redY -> 12001
                        (x and 1) != redX && (y and 1) != redY -> 42003; else -> 28002 }
                    out.writeShort(sample - 32768)
                }
            }
            val decoded = MediaImageFitsCodec.read(file).image
            assertEquals(7, decoded.width); assertEquals(5, decoded.height); assertEquals(3, decoded.channels)
            for (pixel in 0 until 35) assertArrayEquals(shortArrayOf(12001, 28002, 42003.toShort()),
                decoded.pixels.copyOfRange(pixel * 3, pixel * 3 + 3))
            val preview = MediaImageFitsCodec.read(file, maximumSide = 3).image
            assertEquals(3, preview.width); assertEquals(2, preview.height)
            for (i in preview.pixels.indices) assertEquals(intArrayOf(12001, 28002, 42003)[i % 3], preview.unsigned(i))
            MediaImageFitsCodec.open(file).use { source ->
                val tile = source.read(MediaImageRegion(1, 1, 5, 3))
                for (i in tile.pixels.indices) assertEquals(intArrayOf(12001, 28002, 42003)[i % 3], tile.unsigned(i))
            }
        }
    }

    @Test fun floatAndSignedIntegerScalingRemainFractional() = directory { root ->
        for (bitpix in listOf(32, -32)) {
            val source = File(root, "scaled-$bitpix.fit")
            primary(source, 2, 1, bitpix, mapOf("BSCALE" to "0.125", "BZERO" to "0.00001")) { out ->
                if (bitpix == 32) { out.writeInt(-2); out.writeInt(3) }
                else { out.writeFloat(.10001f); out.writeFloat(.10003f) }
            }
            val read = MediaImageFitsCodec.read(source).image
            val expected = if (bitpix == 32) floatArrayOf(-.24999f, .37501f)
                else floatArrayOf((.10001f * .125 + .00001).toFloat(), (.10003f * .125 + .00001).toFloat())
            for (i in expected.indices) assertEquals(expected[i].toDouble(), read.sample(i), read.range.span / 65535 + 1e-8)
        }
    }

    @Test fun numericCurveAndWhiteBalanceOperateWithoutEightBitQuantization() {
        val neutral = MediaImagePixels(3, 1, 1, shortArrayOf(16384, 16386, 53084.toShort()))
        MediaImageProcessing.apply(neutral, MediaImageEditSettings(gamma = 2f), floatArrayOf(1f, 1f, 1f))
        assertEquals(kotlin.math.sqrt(16384.0 / 65535), neutral.sample(0), 2.0 / 65535)
        assertNotEquals(neutral.pixels[0], neutral.pixels[1])
        val original = MediaImagePixels(2, 1, 3, shortArrayOf(19661, 26214, 39321.toShort(), 9831, 13108, 19662))
        val gains = MediaImageProcessing.whiteBalance(original, false, floatArrayOf(1f, 1f, 1f))
        val balanced = original.deepCopy()
        MediaImageProcessing.apply(balanced, MediaImageEditSettings(redGain = gains[0], greenGain = gains[1], blueGain = gains[2]), floatArrayOf(1f, 1f, 1f))
        assertEquals(balanced.unsigned(0).toDouble(), balanced.unsigned(1).toDouble(), 3.0)
        assertEquals(balanced.unsigned(1).toDouble(), balanced.unsigned(2).toDouble(), 3.0)
        assertEquals(19661, original.unsigned(0))
        val identity = original.deepCopy()
        MediaImageProcessing.apply(identity, MediaImageEditSettings(), floatArrayOf(1f, 1f, 1f))
        assertArrayEquals(original.pixels, identity.pixels)
    }

    @Test fun cancelledMalformedAndNonFiniteInputsAreRejected() = directory { root ->
        val file = File(root, "source.fit")
        primary(file, 2, 2, -32) { out -> repeat(4) { out.writeFloat(.5f) } }
        assertThrows(CancellationException::class.java) { MediaImageFitsCodec.read(file, check = { throw CancellationException() }) }
        RandomAccessFile(file, "rw").use { input -> input.seek(2880); input.writeFloat(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { MediaImageFitsCodec.read(file) }
        val truncated = File(root, "truncated.fit").apply { writeBytes(file.readBytes().copyOf(2884)) }
        assertThrows(IllegalArgumentException::class.java) { MediaImageFitsCodec.read(truncated) }
    }

    private fun primary(file: File, width: Int, height: Int, bitpix: Int, extras: Map<String, String> = emptyMap(),
                        pixels: (DataOutputStream) -> Unit) {
        val cards = linkedMapOf("SIMPLE" to "T", "BITPIX" to "$bitpix", "NAXIS" to "2", "NAXIS1" to "$width", "NAXIS2" to "$height") + extras
        val header = cards.entries.joinToString("") { (it.key.padEnd(8) + "= " + it.value).padEnd(80) } + "END".padEnd(80)
        DataOutputStream(file.outputStream()).use { out ->
            out.write(header.padEnd(((header.length + 2879) / 2880) * 2880).toByteArray(Charsets.US_ASCII)); pixels(out)
        }
    }
    private fun directory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("media-edit-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
