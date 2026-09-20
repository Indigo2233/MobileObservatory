package com.indigo.mobileobservatory.camera.toupcam

import org.junit.Assert.assertEquals
import org.junit.Test

class ToupTekDevicesTest {
    @Test
    fun unknownPidStillCountsAsACamera() {
        assertEquals(
            ToupTekDevices.Kind.CAMERA,
            ToupTekDevices.classify(isFilterWheel = false, isAutoFocuser = false)
        )
        assertEquals("ToupTek Camera", ToupTekDevices.cameraDisplayName(null))
        assertEquals("ToupTek Camera", ToupTekDevices.cameraDisplayName("  "))
        assertEquals("ATR26000M", ToupTekDevices.cameraDisplayName("ATR26000M"))
    }

    @Test
    fun accessoriesAreNotListedAsCameras() {
        assertEquals(
            ToupTekDevices.Kind.FILTER_WHEEL,
            ToupTekDevices.classify(isFilterWheel = true, isAutoFocuser = false)
        )
        assertEquals(
            ToupTekDevices.Kind.FOCUSER,
            ToupTekDevices.classify(isFilterWheel = false, isAutoFocuser = true)
        )
    }
}
