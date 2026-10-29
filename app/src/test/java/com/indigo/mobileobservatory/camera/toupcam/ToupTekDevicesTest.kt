package com.indigo.mobileobservatory.camera.toupcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToupTekDevicesTest {
    @Test
    fun gpm585mUsesItsOfficialPidAfterTheActualUsbIdentity() {
        for (vid in listOf(ToupTekDevices.CYPRESS_VENDOR_ID, ToupTekDevices.U3V_VENDOR_ID)) {
            assertEquals(
                listOf(
                    ToupTekDevices.OpenHint(vid, 0x4D55, "GPM585M"),
                    ToupTekDevices.OpenHint(0x0547, 0x1697, "GPM585M")
                ),
                ToupTekDevices.openHints(vid, 0x4D55, "ToupTek GPM-585_M")
            )
        }
    }

    @Test
    fun gpm585mOfficialIdentityIsTriedOnce() {
        assertEquals(
            listOf(ToupTekDevices.OpenHint(0x0547, 0x1697, "GPM585M")),
            ToupTekDevices.openHints(0x0547, 0x1697, "GPM585M")
        )
        assertEquals("GPM585M", ToupTekDevices.hintedModelName(null, "ToupTek GPM585M"))
        assertEquals("ATR585M", ToupTekDevices.hintedModelName("ATR585M"))
    }

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
