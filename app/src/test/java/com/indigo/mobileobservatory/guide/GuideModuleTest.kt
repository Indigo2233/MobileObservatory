package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideModuleTest {
    private val module = GuideModule()

    @Test
    fun detectsBrightStarWithoutAllocatingFullFrameCopy() {
        val width = 64
        val height = 64
        val data = ByteArray(width * height) { 10 }
        for (y in 29..35) {
            for (x in 28..34) {
                val distance = kotlin.math.abs(x - 31) + kotlin.math.abs(y - 32)
                data[y * width + x] = (240 - distance * 20).coerceAtLeast(30).toByte()
            }
        }
        val frame = FrameData(data, width, height, PixelFormat.MONO8, 1, 0)

        val stars = module.detectStars(frame)

        assertTrue(stars.isNotEmpty())
        assertEquals(31f, stars.first().x, 1.5f)
        assertEquals(32f, stars.first().y, 1.5f)
    }

    @Test
    fun matchesWeightedStarTranslation() {
        val reference = listOf(
            GuideStar(10f, 12f, 8f, 100f),
            GuideStar(40f, 30f, 6f, 50f)
        )
        val current = reference.map { it.copy(x = it.x + 3f, y = it.y - 2f) }

        val shift = module.matchShift(reference, current)

        assertNotNull(shift)
        assertEquals(3f, shift!!.x, 0.001f)
        assertEquals(-2f, shift.y, 0.001f)
    }

    @Test
    fun projectsImageErrorThroughCalibrationMatrix() {
        val calibration = GuideCalibration(
            eastXPerMs = 0.01f,
            eastYPerMs = 0f,
            northXPerMs = 0f,
            northYPerMs = 0.02f,
            pulseMs = 1000
        )

        val projected = module.projectError(calibration, dx = 2f, dy = -4f)

        assertNotNull(projected)
        assertEquals(-200f, projected!!.first, 0.01f)
        assertEquals(200f, projected.second, 0.01f)
    }

    @Test
    fun rejectsDegenerateCalibrationMatrix() {
        val calibration = GuideCalibration(0.01f, 0.01f, 0.02f, 0.02f, 1000)

        assertNull(module.projectError(calibration, 1f, 1f))
    }

    @Test
    fun computesRmsAtModuleInterface() {
        val rms = module.rms(
            listOf(
                GuideHistoryPoint(1, 3f, 4f),
                GuideHistoryPoint(2, -3f, -4f)
            )
        )

        assertEquals(3f, rms.raPx, 0.001f)
        assertEquals(4f, rms.decPx, 0.001f)
        assertEquals(5f, rms.totalPx, 0.001f)
    }

    @Test
    fun rmsCentersEachAxisOnItsOwnMeasuredMean() {
        val points = listOf(
            GuideHistoryPoint(1, 4f, -8f, raPulseMs = 100, decPulseMs = -200),
            GuideHistoryPoint(2, 6f, -4f, raPulseMs = -50, decPulseMs = 75)
        )
        val before = points.toList()
        val rms = module.rms(points)

        assertEquals(1f, rms.raPx, .00001f)
        assertEquals(2f, rms.decPx, .00001f)
        assertEquals(kotlin.math.sqrt(5.0).toFloat(), rms.totalPx, .00001f)
        assertEquals(before, points)
    }

    @Test
    fun rmsUsesPopulationVarianceForTheSuppliedSamples() {
        val rms = module.rms(listOf(
            GuideHistoryPoint(1, 2f, -3f),
            GuideHistoryPoint(2, 4f, -3f),
            GuideHistoryPoint(3, 6f, -3f)
        ))
        val expected = kotlin.math.sqrt(8.0 / 3).toFloat()
        assertEquals(expected, rms.raPx, .00001f)
        assertEquals(0f, rms.decPx, 0f)
        assertEquals(expected, rms.totalPx, .00001f)
    }

    @Test
    fun constantOffsetsSingleSamplesAndEmptyWindowsHaveNoMeasuredScatter() {
        for (points in listOf(
            emptyList(),
            listOf(GuideHistoryPoint(1, 3f, -4f)),
            List(50) { GuideHistoryPoint(it.toLong(), 3f, -4f) },
            List(50) { GuideHistoryPoint(it.toLong(), Float.MAX_VALUE, -Float.MAX_VALUE) }
        )) {
            assertEquals(GuideRms(0f, 0f, 0f), module.rms(points))
        }
    }

    @Test
    fun addingIndependentConstantOffsetsDoesNotChangeRms() {
        val points = listOf(
            GuideHistoryPoint(1, 1f, -3f),
            GuideHistoryPoint(2, -2f, 1f),
            GuideHistoryPoint(3, 4f, 0f),
            GuideHistoryPoint(4, -1f, 2f)
        )
        val expected = module.rms(points)
        val shifted = module.rms(points.map { it.copy(raErrorPx = it.raErrorPx + 12.5f, decErrorPx = it.decErrorPx - 7.25f) })
        assertEquals(expected.raPx, shifted.raPx, .00001f)
        assertEquals(expected.decPx, shifted.decPx, .00001f)
        assertEquals(expected.totalPx, shifted.totalPx, .00001f)
    }

    @Test
    fun doublePrecisionCenteredMomentsRetainSmallScatterOnLargeOffsets() {
        val rms = module.rms(listOf(
            GuideHistoryPoint(1, 1_000_000.125f, -2_000_000.25f),
            GuideHistoryPoint(2, 1_000_000.25f, -2_000_000.5f),
            GuideHistoryPoint(3, 1_000_000.375f, -2_000_000.75f)
        ))
        val ra = kotlin.math.sqrt(2 * .125 * .125 / 3)
        val dec = kotlin.math.sqrt(2 * .25 * .25 / 3)
        assertEquals(ra.toFloat(), rms.raPx, .00001f)
        assertEquals(dec.toFloat(), rms.decPx, .00001f)
        assertEquals(kotlin.math.hypot(ra, dec).toFloat(), rms.totalPx, .00001f)
    }

    @Test
    fun hysteresisKeepsSmallFractionOfPreviousCorrection() {
        module.filter(100f, raAxis = true, GuideAlgorithm.HYSTERESIS, aggressiveness = 1.0f)
        val second = module.filter(0f, raAxis = true, GuideAlgorithm.HYSTERESIS, aggressiveness = 1.0f)
        assertEquals(9f, second, 0.01f)
    }

    @Test fun resettingDecDoesNotDiscardRaFilterHistory() {
        module.filter(100f, true, GuideAlgorithm.HYSTERESIS, 1f)
        module.resetFilter(false)
        assertEquals(9f, module.filter(0f, true, GuideAlgorithm.HYSTERESIS, 1f), .001f)
        module.resetFilter(true)
        assertEquals(0f, module.filter(0f, true, GuideAlgorithm.HYSTERESIS, 1f), 0f)
    }

    @Test fun liveCorrectionPolicyRespectsDeadbandEvenWithFilterHistory() {
        val calibration = GuideCalibration(.004f, 0f, 0f, .006f, 1200)
        val backlash = GuideBacklashCompensation()
        module.solveCorrection(calibration, 10f, -10f, GuideAlgorithm.HYSTERESIS, .7f, .7f, .15f, backlash)
        val solution = requireNotNull(module.solveCorrection(calibration, .1f, .1f, GuideAlgorithm.HYSTERESIS, .7f, .7f, .15f, backlash))
        assertEquals(0, solution.correction.raPulseMs); assertEquals(0, solution.correction.decPulseMs)
        org.junit.Assert.assertNull(solution.correction.raDirection); org.junit.Assert.assertNull(solution.correction.decDirection)
        assertEquals(.1f, solution.raErrorPx, .0001f); assertEquals(.1f, solution.decErrorPx, .0001f)
    }

    @Test fun liveCorrectionPolicyLimitsPulsesAndAppliesTheRequestedDirections() {
        val calibration = GuideCalibration(.004f, 0f, 0f, .006f, 1200, decBacklashMs = 1800)
        val backlash = GuideBacklashCompensation().apply { accepted(com.indigo.mobileobservatory.mount.MountDirection.NORTH) }
        val forward = requireNotNull(module.solveCorrection(calibration, 100f, 100f, GuideAlgorithm.HYSTERESIS, .7f, .7f, .15f, backlash)).correction
        assertEquals(2500, forward.raPulseMs); assertEquals(4300, forward.decPulseMs)
        assertEquals(com.indigo.mobileobservatory.mount.MountDirection.WEST, forward.raDirection)
        assertEquals(com.indigo.mobileobservatory.mount.MountDirection.SOUTH, forward.decDirection)
        val reversed = requireNotNull(module.solveCorrection(calibration, 100f, 100f, GuideAlgorithm.HYSTERESIS, .7f, .7f, .15f, backlash,
            reverseRa = true, reverseDec = true)).correction
        assertEquals(com.indigo.mobileobservatory.mount.MountDirection.EAST, reversed.raDirection)
        assertEquals(com.indigo.mobileobservatory.mount.MountDirection.NORTH, reversed.decDirection)
        assertEquals(2500, reversed.decPulseMs)
    }
}
