package com.indigo.mobileobservatory.ui

import com.indigo.mobileobservatory.guide.*
import org.junit.Assert.*
import org.junit.Test

/** Executes the packaged Android JNI library, including the real upstream GP and Z-filter factory. */
class Phd2NativeInstrumentedTest {
    @Test fun packagedPpecCoreProducesFiniteCorrectionsAcrossSeveralWormCycles() {
        val module = GuideModule()
        val parameters = GuideFilterParameters(periodSeconds = 40.0, estimatePeriod = false)
        var error = 0.0
        repeat(100) { i ->
            error += .5 * (kotlin.math.sin(i * 4.0 * Math.PI / 40.0) - kotlin.math.sin((i - 1) * 4.0 * Math.PI / 40.0)) + .01
            val output = module.filter(error.toFloat(), true, GuideAlgorithm.PREDICTIVE_RA, .6f, .05f,
                parameters, 30f, 2.0, i * 2.0)
            assertTrue(output.isFinite())
            error -= output
        }
        assertTrue(kotlin.math.abs(error) < .5)
        module.resetFilter()
    }

    @Test fun packagedZfilterHasIndependentAxisHistoryAndResetsCleanly() {
        val module = GuideModule()
        repeat(20) { module.filter(.5f, true, GuideAlgorithm.Z_FILTER, 1f, .1f) }
        assertEquals(0f, module.filter(0f, false, GuideAlgorithm.Z_FILTER, 1f, .1f), 0f)
        module.resetFilter()
        assertEquals(0f, module.filter(0f, true, GuideAlgorithm.Z_FILTER, 1f, .1f), 0f)
        module.resetFilter()
    }
}
