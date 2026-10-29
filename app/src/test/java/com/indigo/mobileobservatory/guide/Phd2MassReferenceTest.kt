package com.indigo.mobileobservatory.guide

import org.junit.Assert.*
import org.junit.Test

class Phd2MassReferenceTest {
    @Test fun historyAndLimitsMatchTheUnmodifiedCppChecker() {
        val lines = requireNotNull(javaClass.getResourceAsStream("/guide/phd2-mass-reference.csv"))
            .bufferedReader().use { it.readLines() }.drop(1)
        var checker = Phd2MassChecker()
        val limits = DoubleArray(4)
        for (line in lines) {
            val c = line.split(',')
            if (c[1] == "0") checker = Phd2MassChecker()
            limits.fill(Double.NaN)
            val mass = c[5].toDouble()
            var rejected = false
            if (mass >= 0) {
                checker.setExposure(c[3].toInt(), c[4] == "1")
                rejected = checker.checkMass(mass, c[6].toDouble(), limits)
                checker.append(mass, c[2].toLong())
            }
            assertEquals(line, c[7] == "1", rejected)
            for (i in 0..3) {
                val expected = if (c[8 + i].lowercase().contains("nan")) Double.NaN else c[8 + i].toDouble()
                assertEquals("${c[0]}:${c[1]} limit $i", expected, limits[i], 1e-10)
            }
        }
        assertTrue(lines.size > 1500)
    }

    private val reference = GuideStar(32f, 32f, 20f, 100f, 2f)

    private fun assess(module: GuideModule, star: GuideStar? = reference, nowMs: Long = 6000, exposureMs: Int = 1000) =
        module.measureTrackedShift(listOf(reference), listOfNotNull(star), star, exposureMs, nowMs)

    @Test fun massSpikesAndCloudDropsHoldCorrectionsAndHistoryCanRecover() {
        val module = GuideModule()
        repeat(6) { assertNotNull(assess(module, nowMs = it * 1000L)) }
        assertNull(assess(module, reference.copy(x = 33f, flux = 40f)))
        assertEquals(GuideFrameIssue.MASS_CHANGE, module.lastFrameIssue)
        assertEquals(reference, module.acceptedPrimary)
        assertNotNull(assess(module, nowMs = 7000))
        assertNull(assess(module, reference.copy(flux = 300f), 8000))
        assertNotNull(assess(module, nowMs = 9000))
        assertNotNull(assess(module, reference.copy(flux = 40f), 10000, exposureMs = 2000))
        module.resetTrackingQuality()
        assertNotNull(assess(module, reference.copy(flux = 40f), 11000))
    }

    @Test fun secondaryStarsCannotDriveCorrectionsWhileThePrimaryIsLost() {
        val module = GuideModule()
        val secondary = reference.copy(x = 90f)
        assertNull(module.measureTrackedShift(listOf(reference, secondary), listOf(secondary), null, 1000, 1000))
        assertEquals(GuideFrameIssue.PRIMARY_LOST, module.lastFrameIssue)
        assertNull(module.measureTrackedShift(listOf(reference), emptyList(), reference, 1000, 2000))
        assertNotNull(assess(module, nowMs = 3000))
    }

    @Test fun rejectedAndMissingFramesPreserveHysteresisHistory() {
        val module = GuideModule()
        repeat(6) { assess(module, nowMs = it * 1000L) }
        module.filter(1f, true, GuideAlgorithm.HYSTERESIS, .7f, .2f)
        assertNull(assess(module, reference.copy(flux = 40f)))
        assertNull(assess(module, null, 7000))
        assertNotNull(assess(module, nowMs = 8000))
        assertEquals(.2331f, module.filter(.3f, true, GuideAlgorithm.HYSTERESIS, .7f, .2f), 1e-6f)
        module.resetFilter()
    }

    @Test fun aCloudGapPreservesTheLearnedNativePpecModel() {
        val module = GuideModule()
        val parameters = GuideFilterParameters(periodSeconds = 40.0, estimatePeriod = false)
        repeat(6) { assess(module, nowMs = it * 1000L) }
        var position = 0.0
        try {
            for (i in 0..180) {
                position += .5 * (kotlin.math.sin(i * 4.0 * Math.PI / 40) - kotlin.math.sin((i - 1) * 4.0 * Math.PI / 40)) + .01
                position -= module.filter(position.toFloat(), true, GuideAlgorithm.PREDICTIVE_RA, .6f, .05f,
                    parameters, 30f, 2.0, i * 2.0)
            }
            assertNull(assess(module, reference.copy(flux = 40f), 362000))
            assertNull(assess(module, null, 364000))
            assertNotNull(assess(module, nowMs = 366000))
            val predicted = module.filter(0f, true, GuideAlgorithm.PREDICTIVE_RA, .6f, .05f, parameters, 30f, 2.0, 366.0)
            assertTrue(kotlin.math.abs(predicted) > 1e-6f)
        } finally { module.resetFilter() }
    }
}
