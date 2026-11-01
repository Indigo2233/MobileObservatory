package com.indigo.mobileobservatory.stacking

import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class CompactLinearImage16Test {
    private fun encode(image: LinearImage): CompactLinearImage16 {
        val result = CompactLinearImage16(image.width, image.height, image.channels)
        val side = CompactLinearImage16.BLOCK_SIDE
        val tile = FloatArray(side * side * image.channels)
        result.beginFrame()
        for (top in 0 until image.height step side) for (left in 0 until image.width step side) {
            val w = minOf(side, image.width - left)
            val h = minOf(side, image.height - top)
            for (y in 0 until h) {
                val source = ((top + y) * image.width + left) * image.channels
                image.pixels.copyInto(tile, y * w * image.channels, source, source + w * image.channels)
            }
            result.writeBlock(left, top, w, h, tile)
        }
        return result
    }

    @Test fun signedAndAboveWhiteSamplesKeepTheirRangeWithinTheReportedErrorBound() {
        val image = LinearImage(137, 73, 3, FloatArray(137 * 73 * 3) { i ->
            val pixel = i / 3
            (pixel % 137 * .013 + pixel / 137 * .007 - .25 + i % 3 * .12).toFloat()
        })
        val original = image.pixels.copyOf()
        val encoded = encode(image)
        assertTrue(encoded.allFinite())
        assertTrue(encoded.maximumQuantizationError > 0)
        assertEquals(CompactLinearImage16.ENCODING, encoded.encoding)
        for (i in original.indices) {
            assertTrue("$i error=${abs(original[i].toDouble() - encoded.sample(i))}",
                abs(original[i].toDouble() - encoded.sample(i)) <= encoded.maximumQuantizationError + 1e-12)
        }
        assertTrue(encoded.sample(0) < 0)
        assertTrue(encoded.sample(original.lastIndex) > 1)
        assertArrayEquals(original, image.pixels, 0f)
    }

    @Test fun constantChannelsRemainExactIncludingNegativeAndAboveWhiteValues() {
        val levels = floatArrayOf(-.003f, .5f, 1.4f)
        val image = LinearImage(131, 69, 3, FloatArray(131 * 69 * 3) { levels[it % 3] })
        val encoded = encode(image)
        assertEquals(0.0, encoded.maximumQuantizationError, 0.0)
        for (i in image.pixels.indices) assertEquals(image.pixels[i], encoded.sample(i), 0f)
    }

    @Test fun bilinearSamplingAndLuminanceUseEachCornersOwnBlockAndChannel() {
        val image = LinearImage(133, 135, 3, FloatArray(133 * 135 * 3) { i ->
            val pixel = i / 3
            val x = pixel % 133
            val y = pixel / 133
            (.001 * x + .002 * y + (x / 64) * .3 + (y / 64) * .7 + (i % 3) * .15 - .1).toFloat()
        })
        val encoded = encode(image)
        val expected = FloatArray(3)
        val actual = FloatArray(3)
        for ((x, y) in listOf(0 to 0, 63 to 63, 127 to 127, 131 to 133)) {
            image.sampleBilinear(x, y, x + 1, y + 1, .37, .61, expected)
            encoded.sampleBilinear(x, y, x + 1, y + 1, .37, .61, actual)
            for (channel in 0..2) {
                assertTrue(abs(expected[channel].toDouble() - actual[channel]) <=
                    encoded.maximumQuantizationError + 5e-7)
            }
            assertTrue(abs(image.luminanceSample(y * image.width + x) -
                encoded.luminanceSample(y * image.width + x)) <= encoded.maximumQuantizationError + 1e-12)
        }
    }

    @Test fun fullFiniteFloatRangeDoesNotClipOrOverflowTheBlockScale() {
        val image = LinearImage(2, 1, 1, floatArrayOf(-Float.MAX_VALUE, Float.MAX_VALUE))
        val encoded = encode(image)
        assertTrue(encoded.allFinite())
        assertEquals(-Float.MAX_VALUE, encoded.sample(0), 0f)
        assertEquals(Float.MAX_VALUE, encoded.sample(1), 0f)
        assertTrue(encoded.maximumQuantizationError.isFinite())
    }

    @Test fun nonFiniteSamplesAreRejectedBeforeTheyCanBecomeValidUnsignedCodes() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                encode(LinearImage(1, 1, 1, floatArrayOf(value)))
            }
        }
    }

    @Test fun cancellationCanRetryAndTheNextFrameResetsItsQuantizationBound() {
        val encoded = CompactLinearImage16(64, 64, 3)
        val tile = FloatArray(64 * 64 * 3) { it / 1000f }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            encoded.writeBlock(0, 0, 64, 64, tile) { if (++checks == 8) throw CancellationException() }
        }
        encoded.beginFrame()
        encoded.writeBlock(0, 0, 64, 64, tile)
        assertTrue(encoded.maximumQuantizationError > 0)
        tile.fill(-.125f)
        encoded.beginFrame()
        encoded.writeBlock(0, 0, 64, 64, tile)
        assertEquals(0.0, encoded.maximumQuantizationError, 0.0)
        assertEquals(-.125f, encoded.sample(tile.lastIndex), 0f)
    }

    @Test fun imx585Bin2UsesAboutThirteenMegabytesIncludingBlockRanges() {
        val encoded = CompactLinearImage16(1928, 1090, 3)
        assertEquals(12_629_208L, encoded.storageBytes)
        assertEquals(CompactLinearImage16.storageBytes(1928, 1090, 3), encoded.storageBytes)
        assertTrue(encoded.storageBytes < 13_000_000L)
        assertTrue(encoded.storageBytes < 1928L * 1090 * 3 * 4)
    }
}
