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

    @Test
    fun rejectsAnIsolatedHotPixel() {
        val width = 96
        val height = 96
        val pixels = FloatArray(width * height) { 80f }
        pixels[48 * width + 48] = 4000f
        val result = WideFieldStarExtractor.extract(pixels, width, height, maxStars = 20)
        assertTrue(result.stars.none { hypotDist(it.x, it.y, 48f, 48f) < 3f })
    }

    @Test
    fun measuresFaintStarsOnCloudGradientAndRejectsAnEdgeAndHotPixel() {
        val width = 160
        val height = 128
        val cx = 76.35
        val cy = 82.65
        val pixels = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val cloudySky = 70 + 0.85 * y + 0.28 * x
            val star = 18 * kotlin.math.exp(-((x - cx) * (x - cx) + (y - cy) * (y - cy)) / 1.8)
            if (y >= 112) 250f else (cloudySky + star).toInt().toFloat()
        }
        pixels[48 * width + 48] = 255f
        val result = WideFieldStarExtractor.extract(pixels, width, height)

        assertEquals(1, result.stars.size)
        assertEquals(cx, result.stars.single().x.toDouble(), 0.2)
        assertEquals(cy, result.stars.single().y.toDouble(), 0.2)
    }

    @Test
    fun keepsOneAduWingsWhenMeasuringAnUndersampledStar() {
        val width = 64
        val pixels = FloatArray(width * width) { 80f }
        val center = 32
        for (dy in -1..1) {
            for (dx in -1..1) {
                pixels[(center + dy) * width + center + dx] += when (dx * dx + dy * dy) {
                    0 -> 10f
                    1 -> 3f
                    else -> 1f
                }
            }
        }

        val star = WideFieldStarExtractor.extract(pixels, width, width).stars.single()
        assertEquals(center.toFloat(), star.x, 0.01f)
        assertEquals(center.toFloat(), star.y, 0.01f)
        assertEquals(1.0f, star.roundness, 0.01f)
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
