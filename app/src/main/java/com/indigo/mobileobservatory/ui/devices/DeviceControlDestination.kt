package com.indigo.mobileobservatory.ui.devices

import com.indigo.mobileobservatory.accessories.SerialAccessoryRole
import com.indigo.mobileobservatory.camera.AccessoryType

/** Shared destinations for the status strip, equipment list and device controls. */
enum class DeviceControlDestination(val key: String) {
    CAMERA("camera"), MOUNT("mount"), COOLER("cooler"), FILTER("filter"),
    FOCUSER("focuser"), COVER("cover"), ROTATOR("rotator"), POWER("power");

    companion object {
        fun fromKey(key: String): DeviceControlDestination? = entries.firstOrNull { it.key == key }
        val equipment: List<DeviceControlDestination> = entries.filter { it != COOLER }
    }
}

/** A typed connection page must never offer a positively identified incompatible device. */
internal object DeviceConnectionRules {
    fun accepts(
        destination: DeviceControlDestination,
        type: AccessoryType,
        roles: Set<SerialAccessoryRole>?
    ): Boolean {
        if (type == AccessoryType.FILTER_WHEEL) return destination == DeviceControlDestination.FILTER
        if (type == AccessoryType.FOCUSER) return destination == DeviceControlDestination.FOCUSER
        if (type == AccessoryType.EFUCOSER_FOCUSER) return destination == DeviceControlDestination.FOCUSER
        if (type != AccessoryType.SERIAL_DEVICE) return false
        val relevant = when (destination) {
            DeviceControlDestination.FOCUSER -> setOf(SerialAccessoryRole.FOCUSER, SerialAccessoryRole.GEMINI_EAF)
            DeviceControlDestination.COVER -> setOf(SerialAccessoryRole.COVER, SerialAccessoryRole.GEMINI_FLAT)
            DeviceControlDestination.ROTATOR -> setOf(SerialAccessoryRole.ROTATOR, SerialAccessoryRole.WANDERER_ROTATOR)
            DeviceControlDestination.POWER -> setOf(SerialAccessoryRole.GEMINI_POWER)
            else -> return false
        }
        // Unknown devices retain a specific, manual fallback. No automatic role-changing action.
        return roles.isNullOrEmpty() || roles.any { it in relevant }
    }
}
