package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.stacking.ChannelOrder
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class Mp4ColorLayoutTest {
    @Test fun rgb24PrimaryColorsRemainColoredInNv12() {
        val cases = listOf(
            intArrayOf(255, 0, 0) to intArrayOf(82, 90, 240),
            intArrayOf(0, 255, 0) to intArrayOf(144, 54, 34),
            intArrayOf(0, 0, 255) to intArrayOf(41, 240, 110)
        )
        cases.forEach { (rgb, yuv) ->
            val output = Mp4YuvConverter().convert(rgb24(rgb), 4, 4)
            assertEquals(yuv[0], output.y[5].toInt() and 255)
            assertEquals(yuv[1], output.uv[0].toInt() and 255)
            assertEquals(yuv[2], output.uv[1].toInt() and 255)
        }
    }

    @Test fun sixteenBitRgbMatchesRgb24AndHonorsChannelAndByteOrder() {
        val rgb = intArrayOf(192, 80, 32)
        val expected = Mp4YuvConverter().convert(rgb24(rgb), 4, 4)
        val words = java.nio.ByteBuffer.allocate(4 * 4 * 6).order(ByteOrder.BIG_ENDIAN)
        repeat(16) { rgb.reversed().forEach { words.putShort((it shl 8).toShort()) } }
        val frame = FrameData(words.array(), 4, 4, PixelFormat.RGB48, 1, 0)
        val actual = Mp4YuvConverter().convert(frame, 4, 4,
            PixelTraits(validBits = 16, alignment = SampleAlignment.RIGHT, byteOrder = ByteOrder.BIG_ENDIAN, channelOrder = ChannelOrder.BGR))
        assertArrayEquals(expected.y.copyOf(16), actual.y.copyOf(16))
        assertArrayEquals(expected.uv.copyOf(8), actual.uv.copyOf(8))
    }

    @Test fun sdkLeftAndRightAlignedBayerTwelveBitFramesMatch() {
        val right = mosaic(left = false)
        val left = mosaic(left = true)
        val expected = Mp4YuvConverter().convert(right, 4, 4, PixelTraits(validBits = 12, alignment = SampleAlignment.RIGHT))
        val actual = Mp4YuvConverter().convert(left, 4, 4, PixelTraits(validBits = 12, alignment = SampleAlignment.LEFT))
        assertArrayEquals(expected.y.copyOf(16), actual.y.copyOf(16))
        assertArrayEquals(expected.uv.copyOf(8), actual.uv.copyOf(8))
        assertTrue((actual.uv[1].toInt() and 255) > 128)
    }

    private fun rgb24(rgb: IntArray): FrameData = FrameData(
        ByteArray(4 * 4 * 3) { rgb[it % 3].toByte() }, 4, 4, PixelFormat.RGB24, 1, 0)

    private fun mosaic(left: Boolean): FrameData {
        val buffer = java.nio.ByteBuffer.allocate(4 * 4 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until 4) for (x in 0 until 4) {
            val value = when { y % 2 == 0 && x % 2 == 0 -> 192; y % 2 == 1 && x % 2 == 1 -> 32; else -> 80 } shl 4
            buffer.putShort((if (left) value shl 4 else value).toShort())
        }
        return FrameData(buffer.array(), 4, 4, PixelFormat.BAYER_RG12, 1, 0)
    }
}
