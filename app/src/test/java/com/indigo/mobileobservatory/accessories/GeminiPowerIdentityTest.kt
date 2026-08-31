package com.indigo.mobileobservatory.accessories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiPowerIdentityTest {
    @Test
    fun matchesAdvancedV3Identity() {
        assertEquals(
            "GeminiPowerBoxPlusAdv3",
            SerialAccessoryIdentity.geminiPowerIdentity("*HGeminiPowerBoxPlusAdv3")
        )
        assertTrue(SerialAccessoryIdentity.isSupportedGeminiPower("*HGeminiPowerBoxPlusAdv3"))
        assertEquals(
            SerialAccessoryIdentity.GEMINI_POWER_V3_IDENTITY,
            SerialAccessoryIdentity.geminiPowerIdentity("*HGeminiPowerBoxPlusV3")
        )
        assertTrue(SerialAccessoryIdentity.isSupportedGeminiPower("*HGeminiPowerBoxPlusV3"))
        assertTrue(SerialAccessoryIdentity.isSupportedGeminiPower("*HGeminiPowerBoxFuture"))
        assertFalse(SerialAccessoryIdentity.isSupportedGeminiPower("*HGeminiFlatPanel"))
    }
}
