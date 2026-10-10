package com.indigo.mobileobservatory.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPanelSectionsTest {
    @Test
    fun unavailableSectionsAreHidden() {
        val minimal = controlPanelSections(showHostRoi = false, hasCameraInfo = false)

        assertFalse(ControlPanelSection.ROI in minimal)
        assertFalse(ControlPanelSection.INFO in minimal)

        val full = controlPanelSections(showHostRoi = true, hasCameraInfo = true)
        assertTrue(ControlPanelSection.ROI in full)
        assertTrue(ControlPanelSection.INFO in full)
    }
}
