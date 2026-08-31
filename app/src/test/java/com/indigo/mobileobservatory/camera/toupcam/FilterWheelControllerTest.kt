package com.indigo.mobileobservatory.camera.toupcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterWheelControllerTest {

    @Test
    fun acceptsSdkReportedSevenSlotWheel() {
        assertEquals(7, validFilterWheelSlotCount(7))
    }

    @Test
    fun rejectsInvalidSdkSlotCounts() {
        assertNull(validFilterWheelSlotCount(0))
        assertNull(validFilterWheelSlotCount(17))
    }
}
