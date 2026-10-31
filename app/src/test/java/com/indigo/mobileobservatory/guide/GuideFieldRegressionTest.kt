package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.mount.MountDirection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GuideFieldRegressionTest {
    @Test fun crossingTheRaOriginCannotSendAnotherReturnPulse() = runTest {
        var x = 100f
        var y = 100f
        val origin = x
        var crossed = false
        var pulsesAfterCrossing = 0
        val runner = GuideCalibrator(GuideCalibrationConfig(), pulse = { direction, ms ->
            when (direction) {
                MountDirection.WEST -> x -= ms * .004f
                MountDirection.EAST -> {
                    if (crossed) pulsesAfterCrossing++
                    x += ms * .004f
                    // A small measured overshoot beyond the narrow return tolerance.
                    if (!crossed && x - origin >= -.5f) { x = origin + .8f; crossed = true }
                }
                MountDirection.NORTH -> y += ms * .006f
                MountDirection.SOUTH -> y -= ms * .006f
            }
        }, measure = { listOf(GuideStar(x, y, 20f, 100f)) })
        val result = runCatching { runner.run() }
        assertTrue("Test must cross the RA origin", crossed)
        assertEquals("Return pulses must stop on crossing", 0, pulsesAfterCrossing)
        assertTrue("Small overshoot should pass existing drift/rate checks: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test fun automaticRankingPrefersPreciseCompactStarsOverBrightDiffuseOnes() {
        val diffuse = GuideStar(100f, 100f, snr = 5.2f, flux = 10_000f, radiusPx = 7.5f)
        val compact = GuideStar(200f, 100f, snr = 20f, flux = 1_000f, radiusPx = 1.5f)
        assertEquals(compact, GuideModule().usableStars(listOf(diffuse, compact)).first())
    }

    @Test fun manualSelectionUsesScreenDistanceOnAFull585Preview() {
        val viewport = GuideViewport(800f, 450f, 3840, 2160)
        val star = GuideStar(1920f, 1080f, 20f)
        val screen = viewport.toScreen(star.x, star.y)
        // A 12-screen-pixel finger offset is 57.6 sensor pixels at this scale.
        assertEquals(star, viewport.starAt(listOf(star), screen.x + 12, screen.y, 24f))
        assertNull(viewport.starAt(listOf(star), screen.x + 25, screen.y, 24f))
        val zoomed = viewport.copy(zoom = 3f)
        val enlarged = zoomed.toScreen(star.x, star.y)
        assertEquals(star, zoomed.starAt(listOf(star), enlarged.x + 12, enlarged.y, 24f))
    }
}
