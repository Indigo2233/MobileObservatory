package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GainConversionsTest {

    @Test
    fun `toupcam percent converts to documented dB`() {
        assertEquals(0f, GainConversions.toupcamPercentToDb(100f), 0.001f)
        assertEquals(20f, GainConversions.toupcamPercentToDb(1000f), 0.001f)
        assertEquals(40f, GainConversions.toupcamPercentToDb(10_000f), 0.001f)
    }

    @Test
    fun `zwo and player one native units convert to dB`() {
        assertEquals(10f, GainConversions.zwoNativeToDb(100f), 0.001f)
        assertEquals(10f, GainConversions.playerOneNativeToDb(100f), 0.001f)
        assertEquals(60.206f, GainConversions.zwoStopsToNative(1f), 0.001f)
        assertEquals(60.206f, GainConversions.playerOneStopsToNative(1f), 0.001f)
    }

    @Test
    fun `dB equivalent is omitted when the native unit is already dB`() {
        assertNull(GainConversions.dbEquivalent("dB", 12.3f))
        assertEquals(12.3f, GainConversions.dbEquivalent("%", 12.3f) ?: -1f, 0f)
        assertEquals(12.3f, GainConversions.dbEquivalent(null, 12.3f) ?: -1f, 0f)
    }
}
