package com.indigo.mobileobservatory.pointing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WideFieldStarExtractorTest {
    @Test
    fun extractsSyntheticStarsAboveGridMedianBackground() {
        val width = 128
        val height = 128
        val pixels = FloatArray(width * height) { 100f }
        // Mild gradient background
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = 80f + x * 0.1f + y * 0.05f
            }
        }
        paintStar(pixels, width, 40, 50, peak = 400f)
        paintStar(pixels, width, 90, 70, peak = 350f)
        paintStar(pixels, width, 60, 100, peak = 300f)

        val result = WideFieldStarExtractor.extract(
            pixels = pixels,
            width = width,
            height = height,
            maxStars = 20,
            fovWidthDeg = 60.0,
            fovHeightDeg = 45.0
        )

        assertTrue("expected >=3 stars, got ${result.stars.size}", result.stars.size >= 3)
        assertEquals(40f, result.stars.minBy { hypotDist(it.x, it.y, 40f, 50f) }.x, 2f)
        assertTrue(result.estimatedLimitingMagnitude != null)
    }

    @Test
    fun limitingMagnitudeIncreasesWithStarCount() {
        val sparse = WideFieldStarExtractor.estimateLimitingMagnitude(20, 60.0, 45.0)
        val dense = WideFieldStarExtractor.estimateLimitingMagnitude(80, 60.0, 45.0)
        assertTrue(dense > sparse)
        assertTrue(sparse in 3f..10f)
    }

    @Test
    fun emptyFrameReturnsNoStars() {
        val width = 64
        val height = 64
        val pixels = FloatArray(width * height) { 50f }
        val result = WideFieldStarExtractor.extract(pixels, width, height)
        assertTrue(result.stars.isEmpty())
    }

    private fun paintStar(pixels: FloatArray, width: Int, cx: Int, cy: Int, peak: Float) {
        for (dy in -3..3) {
            for (dx in -3..3) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until width) continue
                val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
                val add = (peak * kotlin.math.exp(-dist * dist / 2.5)).toFloat()
                pixels[y * width + x] += add
            }
        }
    }

    private fun hypotDist(x: Float, y: Float, tx: Float, ty: Float): Float =
        kotlin.math.hypot(x - tx, y - ty)
}
