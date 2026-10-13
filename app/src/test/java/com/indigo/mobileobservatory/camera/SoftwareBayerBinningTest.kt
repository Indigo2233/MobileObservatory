package com.indigo.mobileobservatory.camera

import org.junit.Assert.*
import org.junit.Test

class SoftwareBayerBinningTest {
    @Test fun binningKeepsEveryCfaPhaseAtEightAndSixteenBitsForEvenAndOddFactors() {
        val formats = listOf(PixelFormat.BAYER_RG8, PixelFormat.BAYER_BG8, PixelFormat.BAYER_GR8, PixelFormat.BAYER_GB8,
            PixelFormat.BAYER_RG16, PixelFormat.BAYER_BG16, PixelFormat.BAYER_GR16, PixelFormat.BAYER_GB16)
        for (format in formats) for (factor in listOf(2, 3, 4)) for (size in listOf(24 to 24, 11 to 9)) {
            val bpp = format.bytesPerPixel
            val values = if (bpp == 1) intArrayOf(210, 100, 80, 30) else intArrayOf(60000, 32000, 28000, 4000)
            val bytes = ByteArray(size.first * size.second * bpp)
            for (y in 0 until size.second) for (x in 0 until size.first) {
                val index = (y * size.first + x) * bpp
                val value = values[(y and 1) * 2 + (x and 1)]
                bytes[index] = value.toByte()
                if (bpp == 2) bytes[index + 1] = (value ushr 8).toByte()
            }
            val original = bytes.copyOf()
            val binning = SoftwareBinning().apply { this.factor = factor }
            val source = FrameData(bytes, size.first, size.second, format, 1L, 0L)
            val output = binning.apply(source)
            assertEquals(format, output.pixelFormat)
            assertEquals(size.first / factor, output.width)
            assertEquals(size.second / factor, output.height)
            for (y in 0 until output.height) for (x in 0 until output.width) {
                val index = (y * output.width + x) * bpp
                val value = (output.data[index].toInt() and 255) or
                    (if (bpp == 2) (output.data[index + 1].toInt() and 255) shl 8 else 0)
                assertEquals("$format bin$factor at $x,$y", values[(y and 1) * 2 + (x and 1)], value)
            }
            assertArrayEquals(original, source.data)
            assertTrue(binning.release(output.data))
            assertFalse(binning.release(output.data))
            assertFalse(binning.release(source.data))
        }
    }

    @Test fun averagesSpatialSamplesOfTheSamePhaseRatherThanOnlyDecimating() {
        val source = ByteArray(8 * 8) { index -> ((index / 8) * 8 + index % 8).toByte() }
        val binning = SoftwareBinning().apply { factor = 2 }
        val output = binning.apply(FrameData(source, 8, 8, PixelFormat.BAYER_RG8, 1L, 0L))
        // R(0,0) averages source (0,0),(2,0),(0,2),(2,2); G(1,0) uses the other x parity.
        assertEquals((0 + 2 + 16 + 18) / 4, output.data[0].toInt() and 255)
        assertEquals((1 + 3 + 17 + 19) / 4, output.data[1].toInt() and 255)
        assertEquals((8 + 10 + 24 + 26) / 4, output.data[4].toInt() and 255)
    }
}
