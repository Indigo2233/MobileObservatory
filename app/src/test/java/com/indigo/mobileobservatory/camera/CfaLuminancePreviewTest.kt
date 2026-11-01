package com.indigo.mobileobservatory.camera

import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CfaLuminancePreviewTest {
    @Test fun downsamplingComposesSourceCellsWithoutRepeatingOrSkippingAdjacentCellSamples() {
        val pixels = ByteArray(8 * 4) { index -> ((index / 8 / 2) * 100 + (index % 8 / 2) * 20 + 10).toByte() }
        val frame = FrameData(pixels, 8, 4, PixelFormat.BAYER_RG8, 1, 0)
        val preview = CfaLuminancePreview.compose(frame, ByteArray(8), ByteOrder.LITTLE_ENDIAN, sampleStep = 2)
        assertEquals(4, preview.width); assertEquals(2, preview.height)
        assertArrayEquals(byteArrayOf(10, 30, 50, 70, 110, 130.toByte(), 150.toByte(), 170.toByte()), preview.data)
        assertEquals(8, frame.width); assertEquals(4, frame.height)
    }

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

    @Test fun downsamplingAveragesEveryCoveredCellInsteadOfPointSampling() {
        // A single bright cell inside the sampled area must survive the reduction.
        // Point sampling would pick one cell and drop the rest of the star's energy.
        val pixels = ByteArray(8 * 8)
        pixels[3 * 8 + 3] = 200.toByte()
        val preview = CfaLuminancePreview.compose(FrameData(pixels, 8, 8, PixelFormat.BAYER_RG8, 1, 0),
            ByteArray(16), ByteOrder.LITTLE_ENDIAN, sampleStep = 4)
        assertEquals(2, preview.width); assertEquals(2, preview.height)
        // The bright cell is covered by the first output pixel and contributes to its mean.
        assertTrue("The covered bright cell must reach the output pixel",
            (preview.data[0].toInt() and 255) > 0)
        // Averaging spreads the star over the covered area instead of concentrating it.
        assertTrue("The averaged pixel must stay below the source cell value",
            (preview.data[0].toInt() and 255) < 200)
    }

    @Test fun downsamplingKeepsUniformFramesUniform() {
        for (format in listOf(PixelFormat.BAYER_RG8, PixelFormat.BAYER_BG8)) {
            val pixels = ByteArray(16 * 16) { 90.toByte() }
            val preview = CfaLuminancePreview.compose(FrameData(pixels, 16, 16, format, 1, 0),
                ByteArray(64), ByteOrder.LITTLE_ENDIAN, sampleStep = 4)
            assertEquals(4, preview.width); assertEquals(4, preview.height)
            for (y in 0 until preview.height) for (x in 0 until preview.width)
                assertEquals(90, preview.data[y * preview.width + x].toInt() and 255)
        }
    }

    @Test fun downsamplingKeepsEveryOutputPixelInsideTheFrame() {
        for ((width, height) in listOf(5 to 3, 7 to 5, 9 to 6)) {
            val pixels = ByteArray(width * height) { (it % 251).toByte() }
            val preview = CfaLuminancePreview.compose(FrameData(pixels, width, height, PixelFormat.BAYER_RG8, 1, 0),
                ByteArray(width * height), ByteOrder.LITTLE_ENDIAN, sampleStep = 2)
            assertEquals((width / 2).coerceAtLeast(1), preview.width)
            assertEquals((height / 2).coerceAtLeast(1), preview.height)
        }
    }
}
