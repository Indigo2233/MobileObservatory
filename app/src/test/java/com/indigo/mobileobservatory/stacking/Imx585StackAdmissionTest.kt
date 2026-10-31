package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.CameraScienceLayouts
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import org.junit.Assert.assertNull
import org.junit.Test

/** Real 585 geometry: admission must cover the production decoder and renderer together. */
class Imx585StackAdmissionTest {
    private val width = 3856
    private val height = 2180
    private val format = PixelFormat.BAYER_RG16
    private val budget = StackMemoryBudget.forHeap(512L shl 20)

    private fun key(hardwareBin: Int = 1, softwareBin: Int = 1) = StackSessionKey(
        "585", width / hardwareBin, height / hardwareBin, format,
        Roi(0, 0, width / hardwareBin, height / hardwareBin),
        hardwareBin, softwareBin, 5_000_000.0, 100f, "none", "raw",
        pixelTraits = requireNotNull(CameraScienceLayouts.fullContainer(format))
            .copy(bayerPatternAtOutput = hardwareBin > 1)
    )

    @Test fun fullFieldOfflineDeepSkyFitsThe512MiBHeapPlan() {
        val plan = OfflineRegionPlan(width, height, format, PixelRoi(0, 0, width, height),
            DemosaicAlgorithm.DDFAPD, StackTarget.DEEP_SKY)
        println("585 offline full: working=${plan.workingBytes} budget=$budget")
        assertNull(plan.issue(budget))
        assertNull(plan.copy(target = StackTarget.PLANETARY).issue(budget))
    }

    @Test fun verifiedColourBin2FitsLiveDeepSky() {
        val input = key(hardwareBin = 2)
        println("585 live native bin2: working=${LiveStackAdmission.workingBytes(input, 0, false)} budget=$budget")
        assertNull(LiveStackAdmission.issue(input, budget))
    }

    @Test fun fullFieldSoftwareBin2FitsLiveDeepSky() {
        val input = key(softwareBin = 2)
        println("585 live software bin2: working=${LiveStackAdmission.workingBytes(input, 0, false)} budget=$budget")
        assertNull(LiveStackAdmission.issue(input, budget))
    }

    @Test fun fullResolutionLiveDeepSkyFitsThe512MiBHeapPlan() {
        val input = key()
        println("585 live full: working=${LiveStackAdmission.workingBytes(input, 0, false)} budget=$budget")
        assertNull(LiveStackAdmission.issue(input, budget))
    }
}
