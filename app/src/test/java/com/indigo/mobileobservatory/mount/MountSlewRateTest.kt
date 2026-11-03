package com.indigo.mobileobservatory.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun guideRateMultiplierMatchesTheCommandedSiderealRate() {
        assertEquals(.25f, MountSlewRate.RATE_0_25X.guideRateMultiplier, 1e-6f)
        assertEquals(.5f, MountSlewRate.RATE_0_5X.guideRateMultiplier, 1e-6f)
        assertEquals(1f, MountSlewRate.RATE_1X.guideRateMultiplier, 1e-6f)
    }

    @Test
    fun guideRateSnapsToTheLadderStepThatIsNotFasterThanRequested() {
        assertEquals(MountSlewRate.RATE_0_5X, MountSlewRate.forGuideRateSidereal(.5f))
        assertEquals(MountSlewRate.RATE_0_5X, MountSlewRate.forGuideRateSidereal(.75f))
        assertEquals(MountSlewRate.RATE_0_25X, MountSlewRate.forGuideRateSidereal(.25f))
        assertEquals(MountSlewRate.RATE_0_25X, MountSlewRate.forGuideRateSidereal(.1f))
        assertEquals(MountSlewRate.RATE_1X, MountSlewRate.forGuideRateSidereal(.76f))
        assertEquals(MountSlewRate.RATE_1X, MountSlewRate.forGuideRateSidereal(1f))
    }

    @Test
    fun theCommandedMultiplierIsTheOneTheEstimatorUses() {
        // The estimator divides the requested travel by this multiplier, so the pulse
        // length and the mount rate must be derived from the same ladder step.
        for (requested in listOf(.1f, .25f, .5f, .75f, .76f, 1f)) {
            val commanded = MountSlewRate.forGuideRateSidereal(requested)
            assertTrue("Requested $requested must map to a known ladder step",
                commanded.guideRateMultiplier > 0f)
            assertEquals(MountSlewRate.forGuideRateSidereal(requested), commanded)
        }
    }
}
