package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4YuvConverterTest {

    @Test
    fun `copies mono8 into the Y plane and keeps UV neutral`() {
        val pixels = byteArrayOf(10, 20, 30, 40)
        val frame = FrameData(pixels, 2, 2, PixelFormat.MONO8, frameId = 1L, timestamp = 0L)
        val nv12 = Mp4YuvConverter().convert(frame, 2, 2)
        assertEquals(10.toByte(), nv12.y[0])
        assertEquals(40.toByte(), nv12.y[3])
        assertTrue(nv12.uv.all { it == 128.toByte() })
    }

    @Test
    fun `scales a mono frame down with nearest neighbour`() {
        val src = ByteArray(4 * 4) { i -> (i * 3).toByte() }
        val frame = FrameData(src, 4, 4, PixelFormat.MONO8, frameId = 2L, timestamp = 0L)
        val nv12 = Mp4YuvConverter().convert(frame, 2, 2)
        assertEquals(2, nv12.width)
        assertEquals(2, nv12.height)
        assertEquals(src[0], nv12.y[0])
        assertEquals(src[2], nv12.y[1])
        assertEquals(src[8], nv12.y[2])
        assertEquals(src[10], nv12.y[3])
    }

    @Test
    fun `height alignment preserves nearest neighbour luma and chroma rows`() {
        val width = 8; val height = 6; val outHeight = 10
        val frame = FrameData(ByteArray(width * height) { (it * 11).toByte() }, width, height,
            PixelFormat.BAYER_RG8, frameId = 3L, timestamp = 0L)
        val original = Mp4YuvConverter().convert(frame, width, height)
        val scaled = Mp4YuvConverter().convert(frame, width, outHeight)
        val expectedY = ByteArray(width * outHeight) { i ->
            original.y[(i / width * height / outHeight) * width + i % width]
        }
        val expectedUv = ByteArray(width * (outHeight / 2)) { i ->
            original.uv[(i / width * (height / 2) / (outHeight / 2)) * width + i % width]
        }
        assertArrayEquals(expectedY, scaled.y)
        assertArrayEquals(expectedUv, scaled.uv)
    }

    @Test
    fun `parallel Bayer conversion matches sequential conversion across the split`() {
        val width = 1200; val height = 835
        val data = ByteArray(width * height * 2) { ((it * 37 + it / 17) and 255).toByte() }
        for (format in listOf(PixelFormat.BAYER_RG16, PixelFormat.BAYER_GR16,
            PixelFormat.BAYER_GB16, PixelFormat.BAYER_BG16)) {
            val frame = FrameData(data, width, height, format, frameId = 1, timestamp = 0)
            fun converter(parallel: Boolean) = Mp4YuvConverter().apply {
                parallelBayerEnabled = parallel
                wbRedGain = 1.4f; wbGreenGain = .8f; wbBlueGain = 1.2f
            }
            val expected = converter(false).convert(frame, width, height)
            val actual = converter(true).convert(frame, width, height)
            assertArrayEquals(expected.y, actual.y)
            assertArrayEquals(expected.uv, actual.uv)
        }
    }
}
