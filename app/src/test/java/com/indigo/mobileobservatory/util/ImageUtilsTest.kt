package com.indigo.mobileobservatory.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUtilsTest {
    @Test
    fun `integer millisecond exposure omits decimal while fractional value remains visible`() {
        assertEquals("1ms", ImageUtils.formatExposure(1_000f))
        assertEquals("10ms", ImageUtils.formatExposure(10_000f))
        assertEquals("1.5ms", ImageUtils.formatExposure(1_500f))
    }

    @Test
    fun `LP750 matrix maps normalized white and clips output`() {
        assertArrayEquals(intArrayOf(255, 234, 0), ImageUtils.applyLp750Matrix(1f, 1f, 1f))
    }

    @Test
    fun `LP750 matrix clips negative and above-range channels`() {
        assertArrayEquals(intArrayOf(0, 18, 255), ImageUtils.applyLp750Matrix(1f, 0f, 0f))
    }

    @Test
    fun `packed LP750 conversion matches matrix conversion`() {
        val expected = ImageUtils.applyLp750Matrix(0.25f, 0.5f, 0.75f)
        val packed = ImageUtils.lp750Argb(0.25f, 0.5f, 0.75f)

        assertEquals(expected[0], (packed ushr 16) and 0xFF)
        assertEquals(expected[1], (packed ushr 8) and 0xFF)
        assertEquals(expected[2], packed and 0xFF)
    }

    @Test
    fun `LP750 background correction reduces blue residual`() {
        val gains = ImageUtils.lp750BackgroundWhiteBalanceGains(0.30f, 0.32f, 0.33f)

        assertEquals(1.055f, gains[0], 0.01f)
        assertEquals(0.99f, gains[1], 0.01f)
        assertEquals(0.96f, gains[2], 0.01f)
    }

    @Test
    fun `LP750 background correction ignores bright star range`() {
        val pixels = IntArray(100) { 0xFF3C5054.toInt() }
        pixels[0] = 0xFFF04020.toInt()

        ImageUtils.applyLp750BackgroundWhiteBalance(pixels)

        val background = pixels[50]
        assertTrue(kotlin.math.abs(((background ushr 16) and 0xFF) - (background and 0xFF)) <= 3)
        assertEquals(0xFFF04020.toInt(), pixels[0])
    }
}
