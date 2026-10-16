package com.indigo.mobileobservatory.camera.zwo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZwoUsbDevicesTest {
    @Test
    fun am5CdcPortIsNotACameraCandidate() {
        assertFalse(ZwoUsbDevices.isCameraCandidate(0x03C3, 0x4001))
    }

    @Test
    fun unknownZwoPidRemainsForwardCompatible() {
        assertTrue(ZwoUsbDevices.isCameraCandidate(0x03C3, 0x676C))
        assertTrue(ZwoUsbDevices.isCameraCandidate(0x03C3, 0x7FFF))
        assertFalse(ZwoUsbDevices.isCameraCandidate(0x1618, 0x4001))
    }
}
