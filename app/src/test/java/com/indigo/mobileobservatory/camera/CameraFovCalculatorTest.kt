package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CameraFovCalculatorTest {
    @Test
    fun computesFovFromFullActiveArray() {
        val estimate = CameraFovCalculator.estimate(input(outputWidth = 4000, outputHeight = 3000))
        assertNotNull(estimate)
        assertEquals(90.0, estimate!!.widthDeg, 0.1)
        assertEquals(73.74, estimate.heightDeg, 0.1)
    }

    @Test
    fun cropAndSixteenNineOutputReduceField() {
        val full = CameraFovCalculator.estimate(input(4000, 3000))!!
        val cropped = CameraFovCalculator.estimate(input(4000, 2250, cropWidth = 3000, cropHeight = 2250))!!
        assertEquals(73.74, cropped.widthDeg, 0.1)
        assertEquals(45.84, cropped.heightDeg, 0.1)
        org.junit.Assert.assertTrue(cropped.widthDeg < full.widthDeg)
        org.junit.Assert.assertTrue(cropped.heightDeg < full.heightDeg)
    }
    @Test fun exactRetainedRawRectangleReducesTheFieldWithoutASecondCenteredAspectCrop() {
        val reduction = PhoneMonoFrameConverter.plan(4001, 3001, 512, true)
        val footprint = reduction.footprint(0.0, 0.0, 4000.0, 3000.0)
        val sampled = CameraFovCalculator.estimate(input(reduction.outputWidth, reduction.outputHeight).copy(sampledFootprint = footprint))!!
        assertEquals(8.0 * footprint.widthPx / 4000.0, sampled.effectiveSensorWidthMm, 1e-12)
        assertEquals(6.0 * footprint.heightPx / 3000.0, sampled.effectiveSensorHeightMm, 1e-12)
    }

    private fun input(outputWidth: Int, outputHeight: Int, cropWidth: Int = 4000, cropHeight: Int = 3000) = CameraFovInput(
        focalLengthMm = 4.0, sensorWidthMm = 8.0, sensorHeightMm = 6.0,
        activeWidthPx = 4000, activeHeightPx = 3000,
        cropLeftPx = 0, cropTopPx = 0, cropWidthPx = cropWidth, cropHeightPx = cropHeight,
        outputWidthPx = outputWidth, outputHeightPx = outputHeight
    )
}
