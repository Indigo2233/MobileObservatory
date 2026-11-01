package com.indigo.mobileobservatory.guide

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GuideStarViewTest {
    private fun profile(width: Int, height: Int, pixels: List<Float>, bayer: Boolean = false) =
        GuideStarProfile(width, height, pixels, 2f, 100f, pixels.max(), 0f, emptyList(), emptyList(), isBayer = bayer)

    @Test fun rawViewKeepsEverySensorSampleInItsOwnBlock() {
        val pixels = List(16) { it.toFloat() }
        val view = GuideStarView.render(profile(4, 4, pixels), raw = true, scale = 2)
        assertEquals(8, view.width); assertEquals(8, view.height)
        // Each source sample covers a 2x2 block, so the block value never changes.
        for (y in 0 until 8) for (x in 0 until 8)
            assertEquals(pixels[(y / 2) * 4 + (x / 2)], view.pixels[y * 8 + x], .0001f)
    }

    @Test fun interpolatedViewBlendsTheNeighbouringSamples() {
        // A single bright sample surrounded by dark ones.
        val pixels = MutableList(25) { 0f }
        pixels[2 * 5 + 2] = 100f
        val view = GuideStarView.render(profile(5, 5, pixels), raw = false, scale = 4)
        assertEquals(20, view.width); assertEquals(20, view.height)
        val centre = view.pixels[10 * 20 + 10]
        assertTrue("The bright sample must remain visible", centre > 0f)
        assertTrue("The interpolated peak must stay below the raw sample", centre < 100f)
        // A neighbouring output pixel picks up part of the sample.
        assertTrue("The neighbour must receive energy", view.pixels[10 * 20 + 11] > 0f)
    }

    @Test fun theRawViewKeepsThePeakWhileTheInterpolatedViewSpreadsIt() {
        val pixels = List(64) { (it % 7).toFloat() }
        val raw = GuideStarView.render(profile(8, 8, pixels), raw = true, scale = 4)
        val smooth = GuideStarView.render(profile(8, 8, pixels), raw = false, scale = 4)
        // The raw view reproduces the sample values exactly.
        assertEquals(pixels.max(), raw.pixels.max(), .0001f)
        // The interpolated view spreads the peak over the neighbouring pixels.
        assertTrue("The interpolated peak must be lower than the raw peak",
            smooth.pixels.max() < raw.pixels.max())
        assertTrue("The interpolated peak must stay close to the raw peak",
            smooth.pixels.max() > raw.pixels.max() * .5f)
    }

    @Test fun bayerSamplesAreAveragedIntoLuminance() {
        // 2x2 cells alternating between 40 and 180 give a uniform luminance of 110.
        val pixels = List(16) { if (it % 2 == 0) 40f else 180f }
        val luminance = GuideStarView.luminance(profile(4, 4, pixels, bayer = true))
        assertEquals(16, luminance.size)
        assertTrue(luminance.all { abs(it - 110f) < .001f })
    }

    @Test fun theDisplayModeNeverChangesTheMeasuredProfile() {
        val pixels = List(25) { (it * 3).toFloat() }
        val source = profile(5, 5, pixels)
        GuideStarView.render(source, raw = true)
        GuideStarView.render(source, raw = false)
        assertEquals(pixels, source.pixels)
        assertEquals(2f, source.hfrPx, 0f)
    }
}
