package com.indigo.mobileobservatory.camera.toupcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToupTekDevicesTest {
    @Test
    fun consumerScanAcceptsClassicCypressAndU3vVids() {
        assertTrue(ToupTekDevices.isVendor(0x0547))
        assertTrue(ToupTekDevices.isVendor(0x04B4))
        assertTrue(ToupTekDevices.isVendor(0x2BA2))
        assertFalse(ToupTekDevices.isVendor(0x03C3))
        assertTrue(ToupTekDevices.isProtocolVendor(0x0547))
        assertTrue(ToupTekDevices.isProtocolVendor(0x04B4))
        assertFalse(ToupTekDevices.isProtocolVendor(0x2BA2))
    }

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
