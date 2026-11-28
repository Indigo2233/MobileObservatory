package com.indigo.mobileobservatory.ui.components

import androidx.compose.ui.geometry.Rect
import com.indigo.mobileobservatory.camera.Roi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoiEditingTest {
    @Test
    fun spectralStripAlignmentPreservesBothEdges() {
        val source = Roi(0, 0, 6422, 4386)
        val limits = RoiEditingLimits(6422, 4386, 32, 8, 8, 8, 2, 2)
        val strip = source.copy(y = 11, height = 16)
        assertEquals(source.copy(y = 10, height = 24), RoiEditing.fitSpectralStrip(strip, source, limits))
    }

    private val toupcamLimits = RoiEditingLimits(
        sensorWidth = 3840,
        sensorHeight = 2160,
        minWidth = 8,
        minHeight = 8,
        widthStep = 8,
        heightStep = 8,
        offsetXStep = 2,
        offsetYStep = 2
    )

    @Test
    fun toupcamOffsetsCanUseValuesOtherThanMultiplesOfEight() {
        val normalized = RoiEditing.moveX(Roi(8, 0, 1920, 1080), 10, toupcamLimits)

        assertEquals(10, normalized.x)
    }

    @Test
    fun toupcamOffsetsStillHonorTheVendorEvenCoordinateRequirement() {
        assertEquals(12, RoiEditing.moveX(Roi(10, 0, 1920, 1080), 13, toupcamLimits).x)
        assertEquals(18, RoiEditing.moveY(Roi(0, 16, 1920, 1080), 19, toupcamLimits).y)
    }

    @Test
    fun resizingWidthKeepsTheDraftOnDeviceAlignment() {
        val resized = RoiEditing.resizeWidth(Roi(11, 6, 1920, 1080), 1007, toupcamLimits)

        assertEquals(1000, resized.width)
        assertEquals(0, resized.x % 2)
        assertEquals(6, resized.y)
    }

    @Test
    fun proposedRoiIsMappedIntoTheCurrentlyDisplayedCrop() {
        val source = Roi(100, 50, 400, 200)
        val proposed = Roi(200, 100, 100, 50)

        val mapped = RoiEditing.previewRect(proposed, source, 800f, 400f)

        assertEquals(Rect(200f, 100f, 400f, 200f), mapped)
    }

    @Test
    fun proposedRoiOutsideTheCurrentCropHasNoVisibleFrame() {
        val source = Roi(100, 50, 400, 200)
        val proposed = Roi(600, 300, 100, 50)

        assertNull(RoiEditing.previewRect(proposed, source, 800f, 400f))
    }

    @Test
    fun strokeGeometryPlacesItsInnerEdgeOnTheRequestedRoi() {
        val inner = Rect(10f, 20f, 110f, 120f)
        val outer = RoiEditing.outerStrokeRect(inner, strokeWidth = 4f)

        assertEquals(8f, outer.left, 0f)
        assertEquals(18f, outer.top, 0f)
        assertEquals(112f, outer.right, 0f)
        assertEquals(122f, outer.bottom, 0f)
    }

    @Test
    fun fullFrameStrokeStaysInsideTheVisibleImage() {
        val image = Rect(0f, 0f, 640f, 480f)
        val inset = RoiEditing.insetStrokeRect(image, strokeWidth = 5f)

        assertEquals(2.5f, inset.left, 0f)
        assertEquals(2.5f, inset.top, 0f)
        assertEquals(637.5f, inset.right, 0f)
        assertEquals(477.5f, inset.bottom, 0f)
        assertEquals(2.5f, 640f - inset.right, 0f)
        assertEquals(2.5f, 480f - inset.bottom, 0f)
    }

    @Test
    fun fullFrameKeepsASensorWhoseSizeIsNotAlignedToTheRoiStep() {
        val qhyLimits = RoiEditingLimits(
            sensorWidth = 6422,
            sensorHeight = 4386,
            minWidth = 8,
            minHeight = 8,
            widthStep = 8,
            heightStep = 8,
            offsetXStep = 1,
            offsetYStep = 1
        )

        assertEquals(Roi(0, 0, 6422, 4386), RoiEditing.fullFrame(qhyLimits))
        assertEquals(
            Roi(0, 0, 6422, 4386),
            RoiEditing.normalize(Roi(0, 0, 6422, 4386), qhyLimits)
        )
        assertEquals(
            Roi(0, 0, 6422, 4386),
            RoiEditing.clamp(Roi(0, 0, 6422, 4386), qhyLimits)
        )
    }
}
