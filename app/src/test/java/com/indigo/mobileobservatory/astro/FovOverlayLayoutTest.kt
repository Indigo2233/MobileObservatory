package com.indigo.mobileobservatory.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FovOverlayLayoutTest {

    @Test
    fun landscapeSplitsCoreFovOntoVerticalAxis() {
        val fovs = FovOverlayLayout.viewFovsDegrees(1920.0, 1080.0, 60.0)!!
        assertEquals(60.0, fovs.verticalDeg, 1e-9)
        assertTrue(fovs.horizontalDeg > fovs.verticalDeg)
    }

    @Test
    fun portraitSplitsCoreFovOntoHorizontalAxis() {
        val fovs = FovOverlayLayout.viewFovsDegrees(1080.0, 1920.0, 60.0)!!
        assertEquals(60.0, fovs.horizontalDeg, 1e-9)
        assertTrue(fovs.verticalDeg > fovs.horizontalDeg)
    }

    @Test
    fun sensorBoxScalesInverselyWithViewFov() {
        val wide = FovOverlayLayout.sensorBoxPixels(
            viewWidthPx = 1920.0,
            viewHeightPx = 1080.0,
            coreFovDeg = 60.0,
            sensorWidthDeg = 1.2,
            sensorHeightDeg = 0.8
        )!!
        val tight = FovOverlayLayout.sensorBoxPixels(
            viewWidthPx = 1920.0,
            viewHeightPx = 1080.0,
            coreFovDeg = 30.0,
            sensorWidthDeg = 1.2,
            sensorHeightDeg = 0.8
        )!!
        // Landscape: core FOV is vertical → height scales exactly 2× when FOV halves.
        assertEquals(wide.heightPx * 2.0, tight.heightPx, 1e-6)
        // Horizontal FOV is nonlinear in perspective; still must grow on zoom-in.
        assertTrue(tight.widthPx > wide.widthPx * 1.5)
        assertTrue(tight.widthPx < wide.widthPx * 2.5)
    }

    @Test
    fun sensorBoxUsesIndependentAxes() {
        // Square viewport, core FOV 40° → both axes 40°.
        // Sensor 20° × 10° → half width, quarter height of the view.
        val box = FovOverlayLayout.sensorBoxPixels(
            viewWidthPx = 1000.0,
            viewHeightPx = 1000.0,
            coreFovDeg = 40.0,
            sensorWidthDeg = 20.0,
            sensorHeightDeg = 10.0
        )!!
        assertEquals(500.0, box.widthPx, 1e-6)
        assertEquals(250.0, box.heightPx, 1e-6)
    }

    @Test
    fun eyepieceDiameterScalesInverselyWithViewFov() {
        val wide = FovOverlayLayout.eyepieceDiameterPixels(1920.0, 1080.0, 50.0, 1.0)!!
        val tight = FovOverlayLayout.eyepieceDiameterPixels(1920.0, 1080.0, 25.0, 1.0)!!
        assertEquals(wide * 2.0, tight, 1e-6)
        // On the smaller axis (1080): 1° / 50° * 1080 = 21.6 px
        assertEquals(1080.0 * (1.0 / 50.0), wide, 1e-9)
    }

    @Test
    fun zoomInFourTimesGrowsOverlayFourTimes() {
        val at60 = FovOverlayLayout.sensorBoxPixels(800.0, 600.0, 60.0, 2.0, 1.5)!!
        val at15 = FovOverlayLayout.sensorBoxPixels(800.0, 600.0, 15.0, 2.0, 1.5)!!
        // Vertical axis tracks core FOV exactly in landscape.
        assertEquals(4.0, at15.heightPx / at60.heightPx, 1e-6)
        assertTrue(at15.widthPx / at60.widthPx > 3.0)
    }

    @Test
    fun rejectsNonPositiveInputs() {
        assertNull(FovOverlayLayout.viewFovsDegrees(0.0, 100.0, 30.0))
        assertNull(FovOverlayLayout.sensorBoxPixels(100.0, 100.0, 30.0, 0.0, 1.0))
        assertNull(FovOverlayLayout.eyepieceDiameterPixels(100.0, 100.0, 0.0, 1.0))
        assertNull(FovOverlayLayout.eyepieceDiameterPixels(100.0, 100.0, 30.0, -1.0))
    }

    @Test
    fun landscapeSensorMatchesVerticalProportion() {
        // core FOV is vertical in landscape: heightPx = viewH * (sensorH / coreFov)
        val box = FovOverlayLayout.sensorBoxPixels(
            viewWidthPx = 1600.0,
            viewHeightPx = 900.0,
            coreFovDeg = 45.0,
            sensorWidthDeg = 3.0,
            sensorHeightDeg = 2.0
        )!!
        assertEquals(900.0 * (2.0 / 45.0), box.heightPx, 1e-6)
        val fovs = FovOverlayLayout.viewFovsDegrees(1600.0, 900.0, 45.0)!!
        assertEquals(1600.0 * (3.0 / fovs.horizontalDeg), box.widthPx, 1e-6)
        assertTrue(abs(fovs.horizontalDeg - 45.0) > 1.0)
    }
}
