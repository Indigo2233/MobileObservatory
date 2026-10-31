package com.indigo.mobileobservatory.mount

import org.junit.Assert.*
import org.junit.Test

class MountTrackingCodecTest {
    @Test fun onStepEnablementComesFromStatusAndIsIndependentOfTheRate() {
        assertEquals(MountTrackingState(false, MountTrackingRate.OFF), MountTrackingCodec.onStep("nNpEW160", 60.16427))
        assertEquals(MountTrackingState(true, MountTrackingRate.SIDEREAL), MountTrackingCodec.onStep("NpEW160", 60.16427))
        assertEquals(MountTrackingState(true, MountTrackingRate.LUNAR), MountTrackingCodec.onStep("NpEW160", 57.9))
        assertEquals(MountTrackingState(true, MountTrackingRate.SOLAR), MountTrackingCodec.onStep("NpEW160", 60.0))
        assertEquals(MountTrackingState(true), MountTrackingCodec.onStep("NpEW160", 60.136))
        assertEquals(MountTrackingState(true), MountTrackingCodec.onStep("NpEW160"))
        assertEquals(MountTrackingState(true), MountTrackingCodec.onStep("pEW160", 0.0))
        listOf("", "0", "1", "Unknown", "60.16427").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { MountTrackingCodec.onStep(invalid) }
        }
    }

    @Test fun ioptronReadsTheStatusRatherThanTheSavedRateOrGotoFlag() {
        fun status(system: Char, rate: Char = '0') = "+00000000324000002$system${rate}911"
        listOf('0', '6', '7').forEach {
            assertEquals(MountTrackingState(false, MountTrackingRate.OFF), MountTrackingCodec.ioptron(status(it)))
        }
        listOf('1', '3', '5').forEach {
            assertEquals(MountTrackingState(true, MountTrackingRate.SIDEREAL), MountTrackingCodec.ioptron(status(it)))
        }
        assertEquals(MountTrackingState(true, MountTrackingRate.LUNAR), MountTrackingCodec.ioptron(status('1', '1')))
        assertEquals(MountTrackingState(true, MountTrackingRate.SOLAR), MountTrackingCodec.ioptron(status('1', '2')))
        assertEquals(MountTrackingState(true), MountTrackingCodec.ioptron(status('1', '4')))
        listOf('2', '4', '9').forEach {
            assertEquals(MountTrackingState(), MountTrackingCodec.ioptron(status(it)))
        }
        assertThrows(IllegalArgumentException::class.java) { MountTrackingCodec.ioptron("0") }
    }
}
