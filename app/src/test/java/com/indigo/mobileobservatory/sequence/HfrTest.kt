package com.indigo.mobileobservatory.sequence

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class HfrTest {
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
