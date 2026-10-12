package com.indigo.mobileobservatory.util

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ImageUtilsTest {
    @Test
    fun `LP750 matrix maps normalized white and clips output`() {
        assertArrayEquals(intArrayOf(255, 234, 0), ImageUtils.applyLp750Matrix(1f, 1f, 1f))
    }

    @Test
    fun `LP750 matrix clips negative and above-range channels`() {
        assertArrayEquals(intArrayOf(0, 18, 255), ImageUtils.applyLp750Matrix(1f, 0f, 0f))
    }
}
