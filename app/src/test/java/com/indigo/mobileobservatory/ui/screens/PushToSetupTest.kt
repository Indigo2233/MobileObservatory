package com.indigo.mobileobservatory.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushToSetupTest {
    @Test
    fun defaultsRespectSelectedCameraLimits() {
        val settings = defaultPushToSettings(
            cameraId = "wide",
            minimumExposureSeconds = 0.1,
            maximumExposureSeconds = 1.0,
            minimumIso = 100,
            maximumIso = 800,
            supportsRaw = true
        )

        assertEquals("wide", settings.cameraId)
        assertEquals(1.0, settings.exposureSeconds, 1e-9)
        assertEquals(800, settings.iso)
        assertTrue(settings.preferRaw)
    }
}
