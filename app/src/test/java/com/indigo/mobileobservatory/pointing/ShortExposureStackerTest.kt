package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortExposureStackerTest {
    @Test
    fun stackRejectsIsolatedHotPixelAndPreservesPersistentStar() {
        val frames = List(8) { index ->
            val pixels = ByteArray(4) { 10 }
            pixels[1] = 100.toByte() // Same star in every short exposure.
            if (index == 3) pixels[2] = 255.toByte() // One-frame hot pixel.
            FrameData(pixels, 2, 2, PixelFormat.MONO8, index.toLong(), index.toLong())
        }

        val result = ShortExposureStacker.stack(frames)

        assertEquals(100, result.frame.data[1].toInt() and 0xFF)
        assertEquals(10, result.frame.data[2].toInt() and 0xFF)
        assertTrue(result.rejectedHotPixelSamples > 0)
    }

    @Test
    fun stackRequiresMatchingFrameGeometry() {
        val a = FrameData(ByteArray(4), 2, 2, PixelFormat.MONO8, 1, 1)
        val b = FrameData(ByteArray(6), 3, 2, PixelFormat.MONO8, 2, 2)
        try {
            ShortExposureStacker.stack(listOf(a, b))
            throw AssertionError("Expected an invalid burst to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }
}
