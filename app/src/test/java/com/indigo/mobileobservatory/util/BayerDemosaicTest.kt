package com.indigo.mobileobservatory.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BayerDemosaicTest {
    @Test
    fun `all Bayer patterns preserve a flat color field`() {
        val patterns = listOf("RGGB", "GRBG", "GBRG", "BGGR")
        for (pattern in patterns) {
            val raw = IntArray(8 * 8)
            val rX = if (pattern == "RGGB" || pattern == "GBRG") 0 else 1
            val rY = if (pattern == "RGGB" || pattern == "GRBG") 0 else 1
            for (y in 0 until 8) for (x in 0 until 8) {
                raw[y * 8 + x] = when {
                    x % 2 == rX && y % 2 == rY -> 200
                    x % 2 != rX && y % 2 != rY -> 50
                    else -> 100
                }
            }
            val out = BayerDemosaic.demosaic8(raw, 8, 8, pattern, BayerDemosaic.Mode.DIRECTIONAL)
            for ((index, p) in out.withIndex()) {
                assertEquals("$pattern index=$index", 255, p ushr 24)
                assertEquals("$pattern index=$index", 200, (p ushr 16) and 0xFF)
                assertEquals("$pattern index=$index", 100, (p ushr 8) and 0xFF)
                assertEquals("$pattern index=$index", 50, p and 0xFF)
            }
        }
    }

    @Test
    fun `bilinear and directional return complete images`() {
        val raw = IntArray(4 * 4) { it * 7 }
        val bilinear = BayerDemosaic.demosaic8(raw, 4, 4, "RGGB", BayerDemosaic.Mode.BILINEAR)
        val directional = BayerDemosaic.demosaic8(raw, 4, 4, "RGGB", BayerDemosaic.Mode.DIRECTIONAL)
        assertEquals(16, bilinear.size)
        assertEquals(16, directional.size)
        assertTrue(bilinear.all { (it ushr 24) == 255 })
        assertTrue(directional.all { (it ushr 24) == 255 })
    }

    @Test
    fun `caller supplied output buffer is reused`() {
        val raw = IntArray(16) { 128 }
        val output = IntArray(16)

        val result = BayerDemosaic.demosaic8(
            raw, 4, 4, "RGGB", BayerDemosaic.Mode.BILINEAR, output
        )

        assertSame(output, result)
        assertTrue(output.all { it == 0xFF808080.toInt() })
    }
}
