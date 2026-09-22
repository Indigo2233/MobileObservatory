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
        assertTrue(settings.autoIso)
        assertEquals(1, settings.burstFrameCount)
    }

    @Test
    fun halfSecondLensDefaultsToStackedFrames() {
        val settings = defaultPushToSettings(
            cameraId = "uw",
            minimumExposureSeconds = 0.01,
            maximumExposureSeconds = 0.5,
            minimumIso = 50,
            maximumIso = 3200,
            supportsRaw = false
        )
        assertEquals(0.5, settings.exposureSeconds, 1e-9)
        assertEquals(4, settings.burstFrameCount)
    }

    @Test
    fun longerLensesDefaultToOneSecondNotTwo() {
        val settings = defaultPushToSettings(
            cameraId = "main",
            minimumExposureSeconds = 0.01,
            maximumExposureSeconds = 8.0,
            minimumIso = 50,
            maximumIso = 3200,
            supportsRaw = true
        )
        assertEquals(1.0, settings.exposureSeconds, 1e-9)
        assertEquals(1, settings.burstFrameCount)
    }
}
