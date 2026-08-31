package com.indigo.mobileobservatory.accessories.power

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerInterfaceNamesTest {
    @Test
    fun createsStableKeysForEveryInterfaceKind() {
        assertEquals("dc:2", PowerInterfaceNames.dc(2))
        assertEquals("usb-master:6", PowerInterfaceNames.usbMaster(6))
        assertEquals("usb:7", PowerInterfaceNames.usb(7))
        assertEquals("dew:1", PowerInterfaceNames.dew(1))
    }

    @Test
    fun normalizesPersistedNamesAndUsesBlankAsDefaultReset() {
        val longName = "x".repeat(PowerInterfaceNames.MAX_LENGTH + 5)

        assertEquals(
            mapOf(
                "dc:2" to "主相机",
                "dew:1" to "x".repeat(PowerInterfaceNames.MAX_LENGTH)
            ),
            PowerInterfaceNames.normalize(
                mapOf(
                    "dc:2" to "  主相机  ",
                    "usb-master:6" to "   ",
                    "dew:1" to longName,
                    "invalid" to "ignored"
                )
            )
        )
    }
}
