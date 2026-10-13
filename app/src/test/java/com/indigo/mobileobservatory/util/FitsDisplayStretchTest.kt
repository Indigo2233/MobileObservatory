package com.indigo.mobileobservatory.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitsDisplayStretchTest {
    @Test
    fun percentileWindowRejectsSparseOutliers() {
        val values = IntArray(10_000) { 100 + (it % 101) }
        values[0] = -20_000
        values[1] = 40_000

        val window = FitsDisplayStretch.window(values, lowFraction = 0.01, highFraction = 0.99)

        assertTrue(window.black in 100..102)
        assertTrue(window.white in 198..201)
    }

    @Test
    fun normalizedValuesAreClippedToDisplayRange() {
        val window = FitsDisplayStretch.Window(100, 200)

        assertEquals(0f, window.normalize(50), 0f)
        assertEquals(0.5f, window.normalize(150), 0.001f)
        assertEquals(1f, window.normalize(250), 0f)
        assertEquals(0, window.to8(50))
        assertEquals(127, window.to8(150))
        assertEquals(255, window.to8(250))
    }

    @Test
    fun displayToneCurveLowersMidtonesWithoutChangingEndpoints() {
        val pixels = intArrayOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFF04020.toInt(), 0xFFFFFFFF.toInt())

        FitsDisplayStretch.applyDisplayToneCurve(pixels)

        assertEquals(0xFF000000.toInt(), pixels[0])
        assertTrue((pixels[1] and 0xFF) in 84..96)
        assertTrue(kotlin.math.abs(((pixels[2] ushr 16) and 0xFF) * 32 - (pixels[2] and 0xFF) * 240) < 300)
        assertEquals(0xFFFFFFFF.toInt(), pixels[3])
    }
}
