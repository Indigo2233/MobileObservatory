package com.indigo.mobileobservatory.sequence

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HfrTest {
    @Test
    fun binnedLumaPreservesSamplingGridAndRejectsTruncatedFrames() {
        val width = 961
        val height = 7
        for (bytes in 1..2) {
            val raw = ByteArray(width * height * bytes) { (it % 253).toByte() }
            val image = checkNotNull(monoLuma(raw, width, height, bytes))
            assertEquals(3, image.scale)
            assertEquals(320, image.width)
            assertEquals(2, image.height)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val address = (y * 3 * width + x * 3) * bytes
                val expected = address % 253 + if (bytes == 2) 256 * ((address + 1) % 253) else 0
                assertEquals(expected, image.pixels[y * image.width + x])
            }
        }
        org.junit.Assert.assertNull(monoLuma(ByteArray(8), 65_537, 65_537, 2))
    }

    @Test
    fun `gaussian star half light radius stays near the analytic value`() {
        val sigma = 2.5
        val pixels = gaussianStar(width = 48, height = 48, sigma = sigma)
        val measured = medianHalfLightRadius(pixels, 48, 48)
        val expected = expectedGaussianHalfLightRadius(sigma)
        assertTrue(measured != null)
        assertTrue(abs(measured!! - expected) < 0.75)
    }

    @Test
    fun `a blank frame has no half light radius`() {
        val pixels = IntArray(32 * 32) { 20 }
        assertTrue(medianHalfLightRadius(pixels, 32, 32) == null)
        assertTrue(countStars(pixels, 32, 32) == 0)
    }
}
