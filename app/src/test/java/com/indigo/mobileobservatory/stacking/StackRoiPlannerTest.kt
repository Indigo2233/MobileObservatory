package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.floor

class StackRoiPlannerTest {
    private fun context(format: PixelFormat = PixelFormat.BAYER_RG16, bin: Int = 1) = StackRoiContext(
        StackSessionKey("585", 3856, 2180, format, Roi(0, 0, 3856, 2180), 1, bin, 10_000.0, 0f, "none", "raw",
            pixelTraits = CameraScienceLayouts.fullContainer(format) ?: PixelTraits()),
        3856, 2180, 128L shl 20, minWidth = 16, minHeight = 16, widthStep = 4, heightStep = 2)

    @Test fun everyAspectFindsTheLargestAlignedRectangleWithinTheSameStartBudget() {
        val c = context()
        for (aspect in StackRoiAspect.entries) {
            val roi = requireNotNull(StackRoiPlanner.maximum(c, aspect))
            assertNull(c.issue(roi))
            assertEquals(0, roi.width % 4); assertEquals(0, roi.height % 2)
            assertTrue(roi.x >= 0 && roi.y >= 0 && roi.x + roi.width <= c.sensorWidth && roi.y + roi.height <= c.sensorHeight)
            val ratio = when (aspect) { StackRoiAspect.SENSOR -> 3856.0 / 2180; StackRoiAspect.SQUARE -> 1.0
                StackRoiAspect.FOUR_THREE -> 4.0 / 3; StackRoiAspect.SIXTEEN_NINE -> 16.0 / 9 }
            val nextW = roi.width + c.widthStep
            val nextH = floor(nextW / ratio / c.heightStep + 1e-9).toInt() * c.heightStep
            if (nextW <= c.sensorWidth && nextH <= c.sensorHeight) assertNotNull(c.issue(Roi(0, 0, nextW, nextH)))
        }
    }

    @Test fun movingTheMaximumToEitherEdgeKeepsItsSizeAndCfaParity() {
        val c = context()
        val left = requireNotNull(StackRoiPlanner.maximum(c, StackRoiAspect.SENSOR, -10.0, -10.0))
        val right = requireNotNull(StackRoiPlanner.maximum(c, StackRoiAspect.SENSOR, 9999.0, 9999.0))
        assertEquals(0, left.x); assertEquals(0, left.y)
        assertEquals(left.width, right.width); assertEquals(left.height, right.height)
        assertEquals(0, right.x % 8); assertEquals(0, right.y % 8)
        assertTrue(right.x + right.width <= c.sensorWidth && right.y + right.height <= c.sensorHeight)
    }

    @Test fun softwareBinStillPaysForNativeDemosaicAndCalibration() {
        val native = context()
        val binned = context(bin = 2)
        val originalMax = requireNotNull(StackRoiPlanner.maximum(native, StackRoiAspect.SENSOR))
        val binnedMax = requireNotNull(StackRoiPlanner.maximum(binned, StackRoiAspect.SENSOR))
        assertTrue(binnedMax.width > originalMax.width)
        assertTrue(binnedMax.width < originalMax.width * 2)
        val calibrated = binned.copy(calibrationBytes = 24L shl 20, calibrated = true)
        assertTrue(requireNotNull(StackRoiPlanner.maximum(calibrated, StackRoiAspect.SENSOR)).width < binnedMax.width)
        val lowScratch = binned.copy(key = binned.key.copy(demosaicAlgorithm = DemosaicAlgorithm.BILINEAR))
        assertTrue(requireNotNull(StackRoiPlanner.maximum(lowScratch, StackRoiAspect.SENSOR)).width >= binnedMax.width)
    }

    @Test fun unverifiedCfaAndInsufficientMemoryDoNotProduceAnApplyableBox() {
        val c = context()
        assertNull(StackRoiPlanner.maximum(c.copy(key = c.key.copy(hardwareBin = 2)), StackRoiAspect.SENSOR))
        assertNull(StackRoiPlanner.maximum(c.copy(budgetBytes = 1), StackRoiAspect.SENSOR))
    }

    @Test fun monoAboveFourMpCanBeDecodedAndBinnedWhenTheBytesFit() {
        val w = 2056; val h = 2056
        val key = context(PixelFormat.MONO8, 4).key.copy(width = w, height = h, roi = Roi(0, 0, w, h))
        assertTrue(w.toLong() * h > LinearImage.DEFAULT_STACK_PIXELS)
        assertNull(LiveStackAdmission.issue(key, 128L shl 20))
        val frame = FrameData(ByteArray(w * h) { 128.toByte() }, w, h, PixelFormat.MONO8, 1, 0)
        val sensor = LinearPixelDecoder.decodeSensor(frame, key.pixelTraits, maxPixels = LinearImage.MAX_PIXELS)
        val result = LinearPixelDecoder.bin(sensor, 4)
        assertEquals(514, result.width); assertEquals(514, result.height)
        assertTrue(result.pixels.all { it == 128f / 255 })
    }

    @Test fun imageAndRawQueueCeilingsRemainEvenWhenGivenAnUnrealisticByteBudget() {
        val c = context()
        assertEquals("processing_size", LiveStackAdmission.issue(c.key.copy(width = 5000, height = 5000), Long.MAX_VALUE))
        assertNull(LiveStackAdmission.issue(c.key.copy(width = 3000, height = 3000, format = PixelFormat.RGB48), Long.MAX_VALUE))
        assertEquals("processing_size", LiveStackAdmission.issue(c.key.copy(width = 5000, height = 5000, format = PixelFormat.RGB48), Long.MAX_VALUE))
    }

    @Test fun masterAboveFourMpUsesExplicitByteBudgetAndStillRejectsBeforeReadingWhenTooLarge() {
        val w = 2056; val h = 2056
        val geometry = CalibrationGeometry(w, h, "MONO", 8)
        var reads = 0
        val master = CalibrationBuilder.build(CalibrationType.BIAS, geometry, CalibrationCapture(), 1,
            { reads++; LinearImage(w, h, 1, FloatArray(w * h) { .25f }) }, allowUnknownCapture = true,
            memoryBudgetBytes = 128L shl 20)
        assertEquals(1, reads); assertEquals(.25f, master.sample(w * h - 1))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CalibrationBuilder.build(CalibrationType.BIAS, geometry, CalibrationCapture(), 1,
                { error("Must reject before reading") }, allowUnknownCapture = true, memoryBudgetBytes = 64L shl 20)
        }
        assertEquals("memory_budget", failure.message)
    }
}
