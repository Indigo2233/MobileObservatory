package com.indigo.mobileobservatory.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarMapFovOverlayTest {
    @Test
    fun hiddenOverlayClearsBothLayers() {
        val scripts = StarMapFovOverlay.scripts(
            showOverlay = false,
            eyepieceFovDeg = 1.2,
            sensorWidthDeg = 0.8,
            sensorHeightDeg = 0.6,
            alsoZoom = true,
            zoomMode = FovInstrumentMode.SENSOR
        )
        assertEquals(2, scripts.size)
        assertTrue(scripts[0].contains("clearEyepieceFovOverlay"))
        assertTrue(scripts[1].contains("clearSensorFovOverlay"))
    }

    @Test
    fun bothLayersArePushedAndOnlyTheActiveModeZooms() {
        val scripts = StarMapFovOverlay.scripts(
            showOverlay = true,
            eyepieceFovDeg = 1.5,
            sensorWidthDeg = 0.8,
            sensorHeightDeg = 0.5,
            alsoZoom = true,
            zoomMode = FovInstrumentMode.EYEPIECE
        )
        assertTrue(scripts[0].contains("setEyepieceFovOverlay(1.50000000,true)"))
        assertTrue(scripts[1].contains("setSensorFovOverlay(0.80000000,0.50000000,false)"))
    }

    @Test
    fun missingEyepieceClearsTheTelescopeCircle() {
        val scripts = StarMapFovOverlay.scripts(
            showOverlay = true,
            eyepieceFovDeg = null,
            sensorWidthDeg = 1.0,
            sensorHeightDeg = 0.7,
            alsoZoom = false,
            zoomMode = FovInstrumentMode.SENSOR
        )
        assertTrue(scripts[0].contains("clearEyepieceFovOverlay"))
        assertTrue(scripts[1].contains("setSensorFovOverlay(1.00000000,0.70000000,false)"))
        assertFalse(scripts[1].contains(",true)"))
    }

    @Test
    fun sensorModeZoomsPreviewOnly() {
        val scripts = StarMapFovOverlay.scripts(
            showOverlay = true,
            eyepieceFovDeg = 2.0,
            sensorWidthDeg = 0.4,
            sensorHeightDeg = 0.3,
            alsoZoom = true,
            zoomMode = FovInstrumentMode.SENSOR
        )
        assertTrue(scripts[0].contains("setEyepieceFovOverlay(2.00000000,false)"))
        assertTrue(scripts[1].contains("setSensorFovOverlay(0.40000000,0.30000000,true)"))
    }

    @Test
    fun missingSensorClearsThePreviewFrame() {
        val scripts = StarMapFovOverlay.scripts(
            showOverlay = true,
            eyepieceFovDeg = 1.1,
            sensorWidthDeg = null,
            sensorHeightDeg = null,
            alsoZoom = true,
            zoomMode = FovInstrumentMode.SENSOR
        )
        assertTrue(scripts[0].contains("setEyepieceFovOverlay(1.10000000,false)"))
        assertTrue(scripts[1].contains("clearSensorFovOverlay"))
    }
}
