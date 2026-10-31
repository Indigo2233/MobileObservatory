package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random

/** Sources prepared for the next requested validation run; no hardware gain or sky claim. */
class GuideHighGainRegressionTest {
    private val module = GuideModule()

    @Test fun aHotPixelOutsideTheCoreCannotRejectAClearSixteenBitStar() {
        val frame = field { x, y ->
            if (x == 73 && y == 64) 65535 else
                (2000 + 12000 * exp(-((x - 64.2) * (x - 64.2) + (y - 64.1) * (y - 64.1)) / 8)).toInt()
        }
        val stars = module.usableStars(module.detectStars(frame, includeRejected = true))
        val selected = requireNotNull(module.autoSelectStar(stars))
        assertTrue(hypot(selected.x - 64.2f, selected.y - 64.1f) < .3f)
        assertFalse(selected.saturated)
    }

    @Test fun aStrongUnclippedStarOutranksANearbyLowPrecisionNoiseCandidate() {
        val strong = GuideStar(64f, 64f, 118f, 20000f, 2f, peakFraction = .95f)
        val noise = GuideStar(100f, 64f, 20f, 2000f, 1f, peakFraction = .3f)
        assertEquals(strong, module.autoSelectStar(listOf(noise, strong)))
        assertEquals(noise, module.autoSelectStar(listOf(noise, strong.copy(saturated = true))))
    }

    @Test fun amplifiedBayerNoiseAndIsolatedHotPixelsDoNotDisplaceTheRealStar() {
        for (amplification in listOf(1, 10)) {
            val random = Random(585)
            val frame = field { x, y ->
                val background = intArrayOf(600, 900, 900, 450)[(y and 1) * 2 + (x and 1)]
                val star = 2200 * exp(-((x - 64.2) * (x - 64.2) + (y - 64.1) * (y - 64.1)) / 8)
                val noise = random.nextInt(-120, 121)
                if (x in 20..100 && y in 20..100 && x % 19 == 0 && y % 17 == 0) 60000
                else ((background + star + noise) * amplification).toInt()
            }.copy(pixelFormat = PixelFormat.BAYER_RG16)
            val selected = requireNotNull(module.autoSelectStar(module.detectStars(frame)))
            assertTrue("amplification=$amplification, selected=$selected",
                hypot(selected.x - 64.2f, selected.y - 64.1f) < .6f)
        }
    }

    @Test fun anAlreadyLockedStarCanCrossTheClippingThresholdWithoutBeingReportedMissing() {
        val reference = GuideStar(64f, 64f, 118f, 20000f, 2f, hfdPx = 4f)
        val current = reference.copy(x = 64.5f, y = 63.8f, saturated = true)
        assertTrue(module.usableStars(listOf(current)).isEmpty())
        val tracked = module.usableStars(listOf(current), allowSaturated = true)
        val shift = requireNotNull(module.measureTrackedShift(listOf(reference), tracked, current, 1000, 1000))
        assertEquals(.5f, shift.x, .0001f)
        assertEquals(-.2f, shift.y, .0001f)
        assertNull(module.lastFrameIssue)
    }

    @Test fun aClippedSecondaryCannotPullTheGuideErrorAwayFromTheValidPrimary() {
        val primary = GuideStar(64f, 64f, 80f, 20000f, 2f)
        val secondary = primary.copy(x = 100f, flux = 40000f)
        val current = primary.copy(x = 65f)
        val clipped = secondary.copy(x = 104f, saturated = true)
        val shift = requireNotNull(module.measureTrackedShift(
            listOf(primary, secondary), listOf(current, clipped), current, 1000, 1000))
        assertEquals(1f, shift.x, .0001f)
        assertEquals(0f, shift.y, .0001f)
        assertEquals(listOf(current), module.referenceStars(current, listOf(current, clipped), true))
    }

    @Test fun fixedLockFluxCannotPreventAnIdentifiedPrimaryFromRecovering() {
        val references = listOf(
            GuideStar(64f, 64f, 80f, 20000f, 2f),
            GuideStar(100f, 96f, 60f, 10000f, 2f))
        var now = 0L
        for (transmission in listOf(1f, .8f, .6f, .4f, .25f, .15f, .1f)) repeat(50) {
            val current = references.map { it.copy(x = it.x + 2f, y = it.y - 1f, flux = it.flux * transmission) }
            val anchor = module.acceptedPrimary ?: references.first()
            val primary = requireNotNull(module.matchStar(anchor, current, 15f))
            val shift = requireNotNull(module.measureTrackedShift(references, current, primary, 1000, now))
            assertEquals(2f, shift.x, .0001f)
            assertEquals(-1f, shift.y, .0001f)
            now += 1000
        }
    }

    @Test fun guidingSearchCannotHopToADistantPeakWhenThePrimaryDisappears() {
        val reference = GuideStar(64f, 64f, 20f, 1000f, 2f)
        val distant = reference.copy(x = 94f)
        assertNull(module.matchStar(reference, listOf(distant), 15f))
        // Calibration return moves retain the wider, explicitly chosen search.
        assertEquals(distant, module.matchStar(reference, listOf(distant)))
    }

    @Test fun aClippedBackgroundCannotWrapThePhotometryThresholdToDarkPixels() {
        val frame = field { x, y ->
            if ((x - 64) * (x - 64) + (y - 64) * (y - 64) <= 100) 50000
            else if ((x + y) % 2 == 0) 45000 else 65000
        }
        val result = Phd2StarMeasurement.find(GuideImage(frame), 64, 64, maxADU = 65535)
        assertFalse(result.found)
        assertEquals(0.0, result.mass, 0.0)
    }

    private fun field(pixel: (Int, Int) -> Int): FrameData {
        val size = 128
        val bytes = ByteArray(size * size * 2)
        for (y in 0 until size) for (x in 0 until size) {
            val value = pixel(x, y).coerceIn(0, 65535)
            val offset = (y * size + x) * 2
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
        }
        return FrameData(bytes, size, size, PixelFormat.MONO16, 1, 0)
    }
}
