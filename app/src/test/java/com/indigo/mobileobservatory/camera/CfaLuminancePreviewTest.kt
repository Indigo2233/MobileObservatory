package com.indigo.mobileobservatory.camera

import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CfaLuminancePreviewTest {
    @Test fun uniformColorResponseBecomesUniformLuminanceForEveryBayerPattern() {
        for (format in listOf(PixelFormat.BAYER_RG8, PixelFormat.BAYER_GR8, PixelFormat.BAYER_GB8, PixelFormat.BAYER_BG8)) {
            val pixels = ByteArray(8 * 6) { index -> intArrayOf(40, 180, 180, 40)[(index / 8 % 2) * 2 + index % 2].toByte() }
            val original = pixels.clone()
            val frame = FrameData(pixels, 8, 6, format, 17, 1234, captureGeneration = 9)
            val preview = CfaLuminancePreview.compose(frame, ByteArray(pixels.size), ByteOrder.LITTLE_ENDIAN)
            assertTrue(preview.data.all { (it.toInt() and 255) == 110 })
            assertArrayEquals(original, frame.data)
            assertEquals(8, preview.width); assertEquals(6, preview.height)
            assertEquals(PixelFormat.MONO8, preview.pixelFormat)
            assertEquals(frame.frameId, preview.frameId); assertEquals(frame.captureGeneration, preview.captureGeneration)
        }
    }

    @Test fun neighboringCellsRetainTheirBrightnessAndPosition() {
        val frame = FrameData(byteArrayOf(10, 30, 90, 110, 50, 70, 130.toByte(), 150.toByte()),
            4, 2, PixelFormat.BAYER_RG8, 1, 0)
        val preview = CfaLuminancePreview.compose(frame, ByteArray(8), ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals(byteArrayOf(40, 40, 120, 120, 40, 40, 120, 120), preview.data)
    }

    @Test fun oddEdgesUseCompleteCfaCellsWithoutCroppingTheImage() {
        val pixels = ByteArray(5 * 3) { index -> if ((index / 5 + index % 5) % 2 == 0) 40 else 180.toByte() }
        val preview = CfaLuminancePreview.compose(FrameData(pixels, 5, 3, PixelFormat.BAYER_BG8, 1, 0),
            ByteArray(pixels.size), ByteOrder.LITTLE_ENDIAN)
        assertEquals(5, preview.width); assertEquals(3, preview.height)
        assertTrue(preview.data.all { (it.toInt() and 255) == 110 })
    }

    @Test fun highBitMeansPreserveEndiannessAndAvoidOverflow() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            for ((values, expected) in listOf(intArrayOf(0, 10000, 20000, 65535) to 23884, IntArray(4) { 65535 } to 65535)) {
                val data = java.nio.ByteBuffer.allocate(8).order(order).apply { values.forEach { putShort(it.toShort()) } }.array()
                val preview = CfaLuminancePreview.compose(FrameData(data, 2, 2, PixelFormat.BAYER_RG16, 1, 0), ByteArray(8), order)
                val result = java.nio.ByteBuffer.wrap(preview.data).order(order)
                repeat(4) { assertEquals(expected, result.short.toInt() and 65535) }
                assertEquals(PixelFormat.MONO16, preview.pixelFormat)
            }
        }
    }
}
