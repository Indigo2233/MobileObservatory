package com.indigo.mobileobservatory.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCenterMarkerTest {
    @Test
    fun circleIsSizedInImagePixelsAndScalesWithResolution() {
        val hd = PreviewCenterMarker.radiusPx(1920f, 1080f)
        val fourK = PreviewCenterMarker.radiusPx(3840f, 2160f)
        assertEquals(1080f * 0.18f, hd, 0.01f)
        assertEquals(hd * 2f, fourK, 0.01f)
    }

    @Test
    fun strokeStaysAboutTwoScreenPixelsWhenThePreviewIsScaled() {
        val fit = PreviewCenterMarker.strokePx(1f)
        val zoomed = PreviewCenterMarker.strokePx(4f)
        assertEquals(2f, fit, 0.01f)
        assertEquals(0.5f, zoomed, 0.01f)
        assertTrue(zoomed * 4f == fit)
    }

    @Test
    fun radiusFollowsTheShorterImageAxis() {
        assertEquals(1080f * 0.18f, PreviewCenterMarker.radiusPx(1920f, 1080f), 0.01f)
        assertEquals(1080f * 0.18f, PreviewCenterMarker.radiusPx(1080f, 1920f), 0.01f)
    }
}
