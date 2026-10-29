package com.indigo.mobileobservatory.guide

import org.junit.Assert.*
import org.junit.Test

class Phd2FilterReferenceTest {
    @Test fun filterOutputsMatchCompiledUpstreamCppAcrossDeadbandAndDirectionChanges() {
        val rows = requireNotNull(javaClass.getResourceAsStream("/guide/phd2-filter-reference.csv"))
            .bufferedReader().use { it.readLines() }.drop(1)
        var filter = Phd2GuideFilter(true)
        var checked = 0
        for (row in rows) {
            val columns = row.split(',')
            if (columns[2] == "0") { filter.reset(); filter = Phd2GuideFilter(true) }
            val algorithm = GuideAlgorithm.valueOf(columns[0])
            val value = filter.result(columns[3].toDouble(), algorithm, .7, .2,
                GuideFilterParameters(fastSwitch = columns[1] == "1"), 20.0, 2.0, checked * 2.0)
            assertEquals(row, columns[4].toDouble(), value, 1e-10)
            checked++
        }
        assertEquals(196, checked)
    }

    @Test fun aSubMinMoveSampleResetsHysteresisOutputHistory() {
        val filter = Phd2GuideFilter(true)
        val p = GuideFilterParameters()
        filter.result(1.0, GuideAlgorithm.HYSTERESIS, .7, .2, p, 20.0, 2.0, 0.0)
        assertEquals(0.0, filter.result(.1, GuideAlgorithm.HYSTERESIS, .7, .2, p, 20.0, 2.0, 2.0), 0.0)
        assertEquals(.189, filter.result(.3, GuideAlgorithm.HYSTERESIS, .7, .2, p, 20.0, 2.0, 4.0), 1e-12)
    }

    @Test fun nativePpecUsesReactiveGainUntilTheModelHasMeasurements() {
        val filter = Phd2GuideFilter(true)
        assertEquals(.6, filter.result(1.0, GuideAlgorithm.PREDICTIVE_RA, .6, .2,
            GuideFilterParameters(), 20.0, 2.0, 100.0), 1e-12)
        assertEquals(0.0, filter.result(.1, GuideAlgorithm.PREDICTIVE_RA, .6, .2,
            GuideFilterParameters(), 20.0, 2.0, 102.0), 1e-12)
        filter.reset()
    }

    @Test fun nativePpecLearnsARecordedPeriodicSignalUsingMonotonicTimestamps() {
        val filter = Phd2GuideFilter(true)
        val p = GuideFilterParameters(periodSeconds = 40.0, estimatePeriod = false)
        var position = 0.0; var open = 0.0
        val errors = mutableListOf<Double>(); val uncontrolled = mutableListOf<Double>()
        for (i in 0..180) {
            val error = .5 * (kotlin.math.sin(i * 4.0 * Math.PI / 40.0) - kotlin.math.sin((i - 1) * 4.0 * Math.PI / 40.0)) + .01
            position += error; open += error
            errors += position; uncontrolled += open
            val correction = filter.result(position, GuideAlgorithm.PREDICTIVE_RA, .6, .05, p, 30.0, 2.0, i * 2.0)
            assertTrue(correction.isFinite())
            position -= correction
        }
        fun rms(values: List<Double>) = kotlin.math.sqrt(values.sumOf { it * it } / values.size)
        assertTrue("PPEC must reduce the recorded tracking error", rms(errors.takeLast(40)) < rms(uncontrolled.takeLast(40)) * .5)
        val predicted = filter.result(0.0, GuideAlgorithm.PREDICTIVE_RA, .6, .05, p, 30.0, 2.0, 362.0)
        assertTrue("Learned prediction remains active below reactive MinMove", kotlin.math.abs(predicted) > 1e-6)
        filter.reset()
    }
}
