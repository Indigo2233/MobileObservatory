package com.indigo.mobileobservatory.accessories

/**
 * Shared USB-serial identity banners for Indigo DIY accessories.
 *
 * Both EFucoser and electric CAA answer `V#` with `V <n>#` in overlapping
 * numeric ranges, so role detection must use the `#` device banner instead.
 *
 * Gemini EAF (繁星电调) uses `:03#` → `F<ver>#` (INDI MyFocuserPro2 wire protocol).
 */
object SerialAccessoryIdentity {
    val FOCUSER_BANNER = Regex(
        "^EFucoser (?:ESP8266(?: ULN2003)?|Arduino Nano ULN2003) " +
            "Focuser ver (\\d+)$"
    )

    /** ESP8266 CAA and legacy Arduino "scopfocus" rotator banners. */
    val ROTATOR_BANNER = Regex(
        "^(?:CAA .+ |scopfocus )Rotator ver (\\d+)$",
        RegexOption.IGNORE_CASE
    )

    /** Handshake reply to `:03#` from Gemini EAF. */
    val GEMINI_EAF_FIRMWARE = Regex("^F(\\d+)$", RegexOption.IGNORE_CASE)

    fun isFocuserBanner(response: String): Boolean =
        FOCUSER_BANNER.matches(response.trim())

    fun isRotatorBanner(response: String): Boolean =
        ROTATOR_BANNER.matches(response.trim())

    fun isGeminiEafFirmware(response: String): Boolean =
        GEMINI_EAF_FIRMWARE.matches(response.trim())

    fun focuserVersion(response: String): Int? =
        FOCUSER_BANNER.matchEntire(response.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun rotatorVersion(response: String): Int? =
        ROTATOR_BANNER.matchEntire(response.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun geminiEafVersion(response: String): Int? =
        GEMINI_EAF_FIRMWARE.matchEntire(response.trim())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
}
