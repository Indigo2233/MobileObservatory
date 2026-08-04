package com.indigo.mobileobservatory.mount

import org.junit.Assert.assertEquals
import org.junit.Test

class MountSlewRateTest {
    @Test
    fun onStepCommandsUseNumericRates() {
        assertEquals(":R0#", MountSlewRate.RATE_0_25X.command)
        assertEquals(":R2#", MountSlewRate.RATE_1X.command)
        assertEquals(":R3#", MountSlewRate.RATE_2X.command)
        assertEquals(":R4#", MountSlewRate.RATE_4X.command)
        assertEquals(":R9#", MountSlewRate.RATE_MAX.command)
    }

    @Test
    fun classicAliasesMatchOnStepNamedRates() {
        assertEquals(":RG#", MountSlewRate.RATE_1X.classicCommand)
        assertEquals(":RC#", MountSlewRate.RATE_4X.classicCommand)
        assertEquals(":RM#", MountSlewRate.RATE_8X.classicCommand)
        assertEquals(":RS#", MountSlewRate.RATE_MAX.classicCommand)
    }

    @Test
    fun protocolMappingsCoverFullLadder() {
        assertEquals(1, MountSlewRate.RATE_0_25X.ioptronValue)
        assertEquals(9, MountSlewRate.RATE_MAX.ioptronValue)
        assertEquals(0, MountSlewRate.RATE_0_25X.skyWatcherRate)
        assertEquals(9, MountSlewRate.RATE_MAX.skyWatcherRate)
        assertEquals(10, MountSlewRate.entries.size)
    }

    @Test
    fun fromStoredNameMigratesLegacyFourSpeedNames() {
        assertEquals(MountSlewRate.RATE_1X, MountSlewRate.fromStoredName("GUIDE"))
        assertEquals(MountSlewRate.RATE_4X, MountSlewRate.fromStoredName("CENTER"))
        assertEquals(MountSlewRate.RATE_8X, MountSlewRate.fromStoredName("MOVE"))
        assertEquals(MountSlewRate.RATE_MAX, MountSlewRate.fromStoredName("SLEW"))
        assertEquals(MountSlewRate.RATE_2X, MountSlewRate.fromStoredName("RATE_2X"))
        assertEquals(MountSlewRate.DEFAULT, MountSlewRate.fromStoredName("not-a-rate"))
        assertEquals(MountSlewRate.DEFAULT, MountSlewRate.fromStoredName(null))
    }

    @Test
    fun guideAliasIsOneTimesSidereal() {
        assertEquals(MountSlewRate.RATE_1X, MountSlewRate.GUIDE)
        assertEquals(MountSlewRate.RATE_8X, MountSlewRate.DEFAULT)
    }
}
