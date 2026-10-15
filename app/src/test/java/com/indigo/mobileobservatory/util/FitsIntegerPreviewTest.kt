package com.indigo.mobileobservatory.util

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FitsIntegerPreviewTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `phase preserving mapped downsample matches full source scan`() {
        val sourceWidth = 3204
        val sourceHeight = 8
        val physical = IntArray(sourceWidth * sourceHeight) { index ->
            val x = index % sourceWidth
            val y = index / sourceWidth
            1_000 + x * 3 + y * 101
        }
        val file = temporaryFolder.newFile("mapped-bayer.fits")
        writeFits(file, sourceWidth, sourceHeight, physical)

        val preview = FitsIntegerPreviewDecoder.read(file)

        assertEquals(801, preview.width)
        assertEquals(2, preview.height)
        assertEquals("801x2 16bit Bayer(RGGB)", preview.info)

        val expectedRaw = legacyPhasePreservingDownsample(physical, sourceWidth, sourceHeight, 4)
        val window = FitsDisplayStretch.window(expectedRaw)
        val raw8 = IntArray(expectedRaw.size) { window.to8(expectedRaw[it]) }
        val expected = BayerDemosaic.demosaic8(
            raw8,
            preview.width,
            preview.height,
            "RGGB",
            BayerDemosaic.Mode.DIRECTIONAL
        )
        FitsDisplayStretch.applyDisplayToneCurve(expected)

        assertArrayEquals(expected, preview.render(applyLp750 = false))
    }

    @Test
    fun `render cache source remains stable across LP750 toggles`() {
        val width = 8
        val height = 8
        val physical = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                x % 2 == 0 && y % 2 == 0 -> 18_000 + index * 7
                x % 2 == 1 && y % 2 == 1 -> 9_000 + index * 5
                else -> 13_000 + index * 3
            }
        }
        val file = temporaryFolder.newFile("toggle-bayer.fits")
        writeFits(file, width, height, physical)
        val preview = FitsIntegerPreviewDecoder.read(file)

        val normalBefore = preview.render(applyLp750 = false)
        val lp750 = preview.render(applyLp750 = true)
        val normalAfter = preview.render(applyLp750 = false)

        assertArrayEquals(normalBefore, normalAfter)
        assertNotEquals(normalBefore.toList(), lp750.toList())
    }

    private fun writeFits(file: File, width: Int, height: Int, physical: IntArray) {
        val cards = listOf(
            card("SIMPLE", "T"),
            card("BITPIX", "16"),
            card("NAXIS", "2"),
            card("NAXIS1", width.toString()),
            card("NAXIS2", height.toString()),
            card("BZERO", "32768"),
            card("BSCALE", "1"),
            card("BAYERPAT", "'RGGB'"),
            "END".padEnd(80, ' ')
        ).joinToString("")
        val header = cards.padEnd(((cards.length + 2879) / 2880) * 2880, ' ')
        DataOutputStream(FileOutputStream(file)).use { output ->
            output.write(header.toByteArray(Charsets.US_ASCII))
            for (value in physical) output.writeShort(value - 32768)
        }
    }

    private fun card(keyword: String, value: String): String =
        (keyword.padEnd(8, ' ') + "= " + value).padEnd(80, ' ')

    private fun legacyPhasePreservingDownsample(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        scale: Int
    ): IntArray {
        val width = sourceWidth / scale
        val height = sourceHeight / scale
        val sums = LongArray(width * height)
        val counts = IntArray(width * height)
        for (sourceY in 0 until sourceHeight) {
            for (sourceX in 0 until sourceWidth) {
                val x = sourceX / scale
                val y = sourceY / scale
                if (x < width && y < height && sourceX % 2 == x % 2 && sourceY % 2 == y % 2) {
                    val index = y * width + x
                    sums[index] = sums[index] + source[sourceY * sourceWidth + sourceX].toLong()
                    counts[index]++
                }
            }
        }
        return IntArray(sums.size) { (sums[it] / counts[it].coerceAtLeast(1)).toInt() }
    }
}
