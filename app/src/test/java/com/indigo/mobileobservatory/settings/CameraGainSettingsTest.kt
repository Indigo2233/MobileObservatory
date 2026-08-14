package com.indigo.mobileobservatory.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraGainSettingsTest {

    @Test
    fun `reads native gain_value and ignores legacy dB key`() {
        val stored = mapOf(
            "camera.ZWO.gain" to 12.5f,
            "camera.ZWO.gain_value" to 120f
        )

        val value = CameraGainSettings.read(
            contains = stored::containsKey,
            getFloat = stored::getValue,
            prefix = "camera.ZWO."
        )

        assertEquals(120f, value)
    }

    @Test
    fun `does not revive a legacy dB key when native value is absent`() {
        val stored = mapOf("camera.ZWO.gain" to 12.5f)

        val value = CameraGainSettings.read(
            contains = stored::containsKey,
            getFloat = stored::getValue,
            prefix = "camera.ZWO."
        )

        assertNull(value)
    }
}
