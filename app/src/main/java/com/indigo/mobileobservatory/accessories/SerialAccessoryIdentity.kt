package com.indigo.mobileobservatory.accessories

/**
 * Shared USB-serial identity banners for Indigo DIY accessories.
 *
 * Both EFucoser and electric CAA answer `V#` with `V <n>#` in overlapping
 * numeric ranges, so role detection must use the `#` device banner instead.
 *
 * Gemini EAF (繁星电调) uses `:03#` → `F<ver>#` (INDI MyFocuserPro2 wire protocol).
 * Gemini flat panels use `>H#` / `>P000#` handshakes (INDI gemini_flatpanel).
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

    /** Rev1 ping `>P000#` → `*P99OOO` (newline-terminated on wire). */
    val GEMINI_FLAT_REV1_HANDSHAKE = Regex("^\\*P99OOO$")

    /**
     * Rev2 / Lite / Pro ping `>H#` responses (hash stripped by reader).
     * Order matters for matching: Pro and Lite before plain Rev2.
     */
    val GEMINI_FLAT_HASH_HANDSHAKES = listOf(
        Regex("^\\*HGeminiFlatPanelPro$") to "PRO",
        Regex("^\\*HGeminiFlatPanelLite$") to "LITE",
        Regex("^\\*HGeminiFlatPanel$") to "REV2"
    )

    fun isFocuserBanner(response: String): Boolean =
        FOCUSER_BANNER.matches(response.trim())

    fun isRotatorBanner(response: String): Boolean =
        ROTATOR_BANNER.matches(response.trim())

    fun isGeminiEafFirmware(response: String): Boolean =
        GEMINI_EAF_FIRMWARE.matches(response.trim())

    fun isGeminiFlatRev1Handshake(response: String): Boolean =
        GEMINI_FLAT_REV1_HANDSHAKE.matches(response.trim())

    fun isGeminiFlatHandshake(response: String): Boolean =
        isGeminiFlatRev1Handshake(response) ||
            geminiFlatRevisionFromHandshake(response) != null

    /**
     * @return `REV2` / `LITE` / `PRO`, or null if not a `#`-terminated Gemini flat ping.
     */
    fun geminiFlatRevisionFromHandshake(response: String): String? {
        val text = response.trim()
        for ((pattern, revision) in GEMINI_FLAT_HASH_HANDSHAKES) {
            if (pattern.matches(text)) return revision
        }
        return null
    }

    fun focuserVersion(response: String): Int? =
        FOCUSER_BANNER.matchEntire(response.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun rotatorVersion(response: String): Int? =
        ROTATOR_BANNER.matchEntire(response.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun geminiEafVersion(response: String): Int? =
        GEMINI_EAF_FIRMWARE.matchEntire(response.trim())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
}
