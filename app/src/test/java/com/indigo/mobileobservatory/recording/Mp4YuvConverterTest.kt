package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import org.junit.Assert.assertEquals
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
}
