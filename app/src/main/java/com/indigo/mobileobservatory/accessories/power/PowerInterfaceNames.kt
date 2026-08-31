package com.indigo.mobileobservatory.accessories.power

object PowerInterfaceNames {
    const val MAX_LENGTH = 40

    fun dc(commandAddress: Int): String = "dc:$commandAddress"

    fun usbMaster(commandAddress: Int): String = "usb-master:$commandAddress"

    fun usb(commandAddress: Int): String = "usb:$commandAddress"

    fun dew(channel: Int): String = "dew:$channel"

    fun normalize(names: Map<String, String>): Map<String, String> = buildMap {
        names.forEach { (key, value) ->
            val normalized = value.trim().take(MAX_LENGTH)
            if (key.matches(KEY_PATTERN) && normalized.isNotEmpty()) {
                put(key, normalized)
            }
        }
    }

    private val KEY_PATTERN = Regex("^(dc|usb-master|usb|dew):\\d+$")
}
