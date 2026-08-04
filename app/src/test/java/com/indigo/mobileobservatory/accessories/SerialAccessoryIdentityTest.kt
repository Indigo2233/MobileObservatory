package com.indigo.mobileobservatory.accessories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialAccessoryIdentityTest {
    @Test
    fun focuserBannersMatchKnownFirmware() {
        assertEquals(
            1103,
            SerialAccessoryIdentity.focuserVersion("EFucoser ESP8266 ULN2003 Focuser ver 1103")
        )
        assertEquals(
            1005,
            SerialAccessoryIdentity.focuserVersion("EFucoser ESP8266 Focuser ver 1005")
        )
        assertEquals(
            1201,
            SerialAccessoryIdentity.focuserVersion("EFucoser Arduino Nano ULN2003 Focuser ver 1201")
        )
    }

    @Test
    fun rotatorBannersMatchKnownFirmware() {
        assertEquals(
            2007,
            SerialAccessoryIdentity.rotatorVersion("CAA ESP8266 Rotator ver 2007")
        )
        assertEquals(
            1013,
            SerialAccessoryIdentity.rotatorVersion("scopfocus Rotator ver 1013")
        )
    }

    @Test
    fun versionOnlyReplyIsNotEnoughToClassifyRole() {
        // Both firmwares answer V# this way â€?must not be treated as identity.
        assertFalse(SerialAccessoryIdentity.isFocuserBanner("V 1103"))
        assertFalse(SerialAccessoryIdentity.isRotatorBanner("V 2007"))
        assertFalse(SerialAccessoryIdentity.isRotatorBanner("V 1103"))
    }

    @Test
    fun focuserAndRotatorBannersDoNotCrossMatch() {
        val focuser = "EFucoser ESP8266 ULN2003 Focuser ver 1103"
        val rotator = "CAA ESP8266 Rotator ver 2007"
        assertTrue(SerialAccessoryIdentity.isFocuserBanner(focuser))
        assertFalse(SerialAccessoryIdentity.isRotatorBanner(focuser))
        assertTrue(SerialAccessoryIdentity.isRotatorBanner(rotator))
        assertFalse(SerialAccessoryIdentity.isFocuserBanner(rotator))
    }

    @Test
    fun geminiEafFirmwareHandshakeMatches() {
        assertEquals(291, SerialAccessoryIdentity.geminiEafVersion("F291"))
        assertEquals(320, SerialAccessoryIdentity.geminiEafVersion("f320"))
        assertTrue(SerialAccessoryIdentity.isgeminiEafFirmware("F291"))
        assertFalse(SerialAccessoryIdentity.isgeminiEafFirmware("V 1103"))
        assertFalse(SerialAccessoryIdentity.isgeminiEafFirmware(
            "EFucoser ESP8266 ULN2003 Focuser ver 1103"
        ))
        assertFalse(SerialAccessoryIdentity.isFocuserBanner("F291"))
    }
}
