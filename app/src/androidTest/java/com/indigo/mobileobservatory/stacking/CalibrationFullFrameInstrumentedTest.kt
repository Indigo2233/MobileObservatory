package com.indigo.mobileobservatory.stacking

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indigo.mobileobservatory.camera.PixelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalibrationFullFrameInstrumentedTest {
    @Test fun fullFrameFlatBiasAndDarkFitInTheMeasuredLargeHeapBudget() {
        val geometry = CalibrationGeometry(3536, 3536, "RGGB", 16)
        val heapLimit = Runtime.getRuntime().maxMemory()
        val budget = StackMemoryBudget.forHeap(heapLimit)
        val capture = CalibrationCapture("IMX585", 120_000_000.0, 100.0, 0.0, 0, 0, 1, "L", "RAW", -10.0)
        val residentBias = geometry.sampleCount * 4L
        val residentDark = geometry.sampleCount * 4L
        val available = budget - residentBias - residentDark
        val estimate = CalibrationBuilder.estimatedBuildBytes(geometry, withSampleCounts = true)

        assertTrue("heap=$heapLimit budget=$budget available=$available estimate=$estimate",
            available >= estimate)
        val flat = CalibrationBuilder.build(CalibrationType.FLAT, geometry, capture, 2,
            { frame ->
                LinearImage(geometry.width, geometry.height, 1,
                    FloatArray(geometry.sampleCount) { index ->
                        0.1f + 0.01f * ((index + frame) % 7)
                    })
            },
            bias = CalibrationMaster(CalibrationDescriptor(
                "bias", "bias", CalibrationType.BIAS, geometry, capture, "bias", 1, 1
            ), FloatArray(geometry.sampleCount) { 0.05f }),
            memoryBudgetBytes = available)

        assertEquals(CalibrationType.FLAT, flat.descriptor.type)
        assertEquals(2, flat.descriptor.frameCount)
    }
}
