package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.CameraScienceLayouts
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import org.junit.Assert.*
import org.junit.Test

/** A full 585 frame exceeding the available budget must expose an admitted ROI. */
class LiveStackDiagnosticTest {
    @Test fun fullFrameBudgetFailureOffersACenteredRoiThatPassesTheSameAdmission() {
        val format = PixelFormat.BAYER_RG16
        val width = 3856
        val height = 2180
        val key = StackSessionKey("585 diagnostic", width, height, format,
            Roi(0, 0, width, height), 1, 1, 2_500_000.0, 255f, "none", "HCG",
            pixelTraits = requireNotNull(CameraScienceLayouts.fullContainer(format)))
        val available = 250L shl 20
        val failure = LiveInputDiagnostic("", key, available,
            LiveStackAdmission.workingBytes(key, 0, false), 0, false)
        assertTrue(failure.workingBytes > failure.budgetBytes)
        val context = StackRoiContext(key, width, height, available,
            minWidth = 16, minHeight = 16, widthStep = 4, heightStep = 2)
        val roi = requireNotNull(StackRoiPlanner.maximum(context, StackRoiAspect.SENSOR))
        assertNull(context.issue(roi))
        assertTrue(roi.width < width)
        assertTrue(roi.height < height)
        assertEquals(0, roi.width % 4)
        assertEquals(0, roi.height % 2)
    }
}
