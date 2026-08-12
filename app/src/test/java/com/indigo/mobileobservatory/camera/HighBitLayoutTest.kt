package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class HighBitLayoutTest {
    @Test
    fun detectsLeftAligned10BitData() {
        val layout = detectHighBitLayout(maxValue = 65472, lowBitsMask = 0xFFC0, declaredBits = 16)

        assertEquals(10, layout.effectiveBits)
        assertEquals(6, layout.shift)
    }

    @Test
    fun detectsLeftAligned12BitDataAtLowExposure() {
        val layout = detectHighBitLayout(maxValue = 1072, lowBitsMask = 0x0430, declaredBits = 16)

        assertEquals(12, layout.effectiveBits)
        assertEquals(6, layout.shift)
    }

    @Test
    fun keepsDimRightAlignedDataUnclassified() {
        val layout = detectHighBitLayout(maxValue = 900, lowBitsMask = 0x0387, declaredBits = 16)

        assertEquals(16, layout.effectiveBits)
        assertEquals(0, layout.shift)
    }

    @Test
    fun keepsBlackFrameUnclassified() {
        val layout = detectHighBitLayout(maxValue = 0, lowBitsMask = 0, declaredBits = 16)

        assertEquals(16, layout.effectiveBits)
        assertEquals(0, layout.shift)
    }
}
