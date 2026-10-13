package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LinearPixelDecoderTest {
    private fun frame(format: PixelFormat, values: IntArray, w: Int = values.size, h: Int = 1,
                      order: ByteOrder = ByteOrder.LITTLE_ENDIAN): FrameData {
        val buffer = ByteBuffer.allocate(values.size * if (format.isHighBit) 2 else 1).order(order)
        for (v in values) if (format.isHighBit) buffer.putShort(v.toShort()) else buffer.put(v.toByte())
        return FrameData(buffer.array(), w, h, format, 1, 0)
    }

    @Test fun allMonoDepthsPreserveAdjacentCodeValuesInBothAlignmentsAndByteOrders() {
        for (bits in listOf(8, 10, 12, 14, 16)) for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val max = (1 shl bits) - 1
            val values = intArrayOf(0, max / 2, max / 2 + 1, max)
            val format = PixelFormat.valueOf("MONO$bits")
            for (alignment in listOf(SampleAlignment.LEFT, SampleAlignment.RIGHT)) {
                val shift = if (alignment == SampleAlignment.LEFT && bits > 8) 16 - bits else 0
                val input = frame(format, values.map { it shl shift }.toIntArray(), order = order)
                val image = LinearPixelDecoder.decode(input, PixelTraits(byteOrder = order, alignment = alignment))
                for (i in values.indices) assertEquals(values[i].toFloat() / max, image.pixels[i], 1e-7f)
                assertTrue(image.pixels[2] > image.pixels[1])
            }
        }
    }

    @Test fun allBayerPhasesAndRoiParitiesKeepSixteenBitColorIncludingBorders() {
        for (pattern in listOf("RG", "GR", "GB", "BG")) for (ox in 0..1) for (oy in 0..1) {
            val redX = if (pattern == "GR" || pattern == "BG") 1 else 0
            val redY = if (pattern == "GB" || pattern == "BG") 1 else 0
            val rgb = intArrayOf(48001, 23002, 9003)
            val values = IntArray(36) { i ->
                val x = (i % 6 + ox) and 1; val y = (i / 6 + oy) and 1
                rgb[if (x == redX && y == redY) 0 else if (x != redX && y != redY) 2 else 1]
            }
            val image = LinearPixelDecoder.decode(frame(PixelFormat.valueOf("BAYER_${pattern}16"), values, 6, 6),
                PixelTraits(roiX = ox, roiY = oy, bayerPatternAtOutput = false))
            for (i in 0 until 36) for (c in 0..2) assertEquals(rgb[c] / 65535f, image.pixels[3 * i + c], 1e-6f)
            val binned = LinearPixelDecoder.bin(image, 2)
            for (i in 0 until 9) for (c in 0..2) assertEquals(rgb[c] / 65535f, binned.pixels[3 * i + c], 1e-6f)
        }
    }

    @Test fun rgb48BgrAndRgb24HaveCorrectChannelOrder() {
        val high = frame(PixelFormat.RGB48, intArrayOf(12345, 23456, 34567), 1, 1, ByteOrder.BIG_ENDIAN)
        val result = LinearPixelDecoder.decode(high, PixelTraits(byteOrder = ByteOrder.BIG_ENDIAN, channelOrder = ChannelOrder.BGR))
        assertArrayEquals(floatArrayOf(34567 / 65535f, 23456 / 65535f, 12345 / 65535f), result.pixels, 1e-7f)
        val low = frame(PixelFormat.RGB24, intArrayOf(1, 2, 3), 1, 1)
        assertArrayEquals(floatArrayOf(1 / 255f, 2 / 255f, 3 / 255f), LinearPixelDecoder.decode(low).pixels, 1e-7f)
    }

    @Test fun explicitAlignmentKeepsDimLeftAlignedDataStableAcrossFrames() {
        val image = LinearPixelDecoder.decode(frame(PixelFormat.MONO12, intArrayOf(16, 32, 48)),
            PixelTraits(alignment = SampleAlignment.LEFT))
        assertArrayEquals(floatArrayOf(1 / 4095f, 2 / 4095f, 3 / 4095f), image.pixels, 1e-8f)
    }

    @Test fun ambiguousBinningTruncationAndBudgetAreRejectedBeforeAllocation() {
        val bayer = frame(PixelFormat.BAYER_RG8, IntArray(16), 4, 4)
        assertThrows(IllegalArgumentException::class.java) {
            LinearPixelDecoder.decode(bayer, PixelTraits(binning = 2, bayerPatternAtOutput = false))
        }
        assertThrows(IllegalArgumentException::class.java) { LinearPixelDecoder.decode(bayer, maxPixels = 15) }
        assertThrows(IllegalArgumentException::class.java) { LinearPixelDecoder.decode(bayer.copy(data = ByteArray(2))) }
    }

    @Test fun decodingOwnsDataAndCannotBeMutatedByCameraBufferReuse() {
        val frame = frame(PixelFormat.MONO16, intArrayOf(1031, 1032, 1033))
        val image = LinearPixelDecoder.decode(frame)
        frame.data.fill(0)
        assertEquals(1032 / 65535f, image.pixels[1], 1e-8f)
    }
}
