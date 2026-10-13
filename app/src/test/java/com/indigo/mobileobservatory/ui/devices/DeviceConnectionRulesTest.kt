package com.indigo.mobileobservatory.ui.devices

import com.indigo.mobileobservatory.accessories.SerialAccessoryRole
import com.indigo.mobileobservatory.camera.AccessoryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceConnectionRulesTest {
    @Test
    fun identifiedCoverIsNotOfferedForFocuserOrPower() {
        val roles = setOf(SerialAccessoryRole.GEMINI_FLAT)
        assertTrue(DeviceConnectionRules.accepts(DeviceControlDestination.COVER, AccessoryType.SERIAL_DEVICE, roles))
        assertFalse(DeviceConnectionRules.accepts(DeviceControlDestination.FOCUSER, AccessoryType.SERIAL_DEVICE, roles))
        assertFalse(DeviceConnectionRules.accepts(DeviceControlDestination.POWER, AccessoryType.SERIAL_DEVICE, roles))
    }

    @Test
    fun unknownSerialDeviceRetainsTargetedManualRecovery() {
        listOf(null, emptySet<SerialAccessoryRole>()).forEach { roles ->
            assertTrue(DeviceConnectionRules.accepts(DeviceControlDestination.FOCUSER, AccessoryType.SERIAL_DEVICE, roles))
            assertTrue(DeviceConnectionRules.accepts(DeviceControlDestination.COVER, AccessoryType.SERIAL_DEVICE, roles))
            assertFalse(DeviceConnectionRules.accepts(DeviceControlDestination.FILTER, AccessoryType.SERIAL_DEVICE, roles))
        }
    }

    @Test
    fun multiRoleDeviceExposesOnlyItsActualCapabilities() {
        val roles = setOf(SerialAccessoryRole.GEMINI_EAF, SerialAccessoryRole.GEMINI_POWER)
        assertTrue(DeviceConnectionRules.accepts(DeviceControlDestination.FOCUSER, AccessoryType.SERIAL_DEVICE, roles))
        assertTrue(DeviceConnectionRules.accepts(DeviceControlDestination.POWER, AccessoryType.SERIAL_DEVICE, roles))
        assertFalse(DeviceConnectionRules.accepts(DeviceControlDestination.ROTATOR, AccessoryType.SERIAL_DEVICE, roles))
    }

    @Test
    fun nativeFilterWheelCannotBecomeAFocuserFallback() {
        assertTrue(DeviceConnectionRules.accepts(DeviceControlDestination.FILTER, AccessoryType.FILTER_WHEEL, null))
        assertFalse(DeviceConnectionRules.accepts(DeviceControlDestination.FOCUSER, AccessoryType.FILTER_WHEEL, null))
        assertTrue(DeviceConnectionRules.accepts(DeviceControlDestination.FOCUSER, AccessoryType.FOCUSER, null))
    }
}
