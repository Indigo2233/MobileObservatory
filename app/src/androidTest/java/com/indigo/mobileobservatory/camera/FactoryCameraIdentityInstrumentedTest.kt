package com.indigo.mobileobservatory.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FactoryCameraIdentityInstrumentedTest {
    @Test fun unavailableOrInvalidCameraCannotFabricateAFactorySerialFromRuntimeId() {
        assertEquals("", UsbHelper.zwoFactorySerial(-1))
        assertEquals("", UsbHelper.zwoFactorySerial(Int.MAX_VALUE))
    }
}
