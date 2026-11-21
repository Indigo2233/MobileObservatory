package com.indigo.mobileobservatory.camera.toupcam

import com.indigo.mobileobservatory.camera.ReadoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToupcamReadoutModesTest {
    @Test fun g3m678mDoesNotAdvertiseHdrEvenWhenTheSdkFlagIsSet() {
        val modes = ToupcamReadoutModes.advertised(
            modelName = "G3M678M",
            hasCg = true,
            hasCgHdr = true,
            hasLowNoise = false
        )
        assertEquals(listOf(ReadoutMode.LCG, ReadoutMode.HCG), modes)
        assertFalse(ReadoutMode.HDR in modes)
    }

    @Test fun g3m678mLcgFallsBackToNormalWhenNoConversionGainFlagIsSet() {
        val restored = ToupcamReadoutModes.restoredModel(
            storedCg = 2,
            advertised = listOf(ReadoutMode.NORMAL),
            writable = true
        )
        assertEquals(0, restored.write)
        assertEquals(ReadoutMode.NORMAL, restored.current)
    }

    @Test fun documentedHdrModelKeepsItsStoredMode() {
        val restored = ToupcamReadoutModes.restoredModel(
            storedCg = 2,
            advertised = listOf(ReadoutMode.LCG, ReadoutMode.HCG, ReadoutMode.HDR),
            writable = true
        )
        assertEquals(null, restored.write)
        assertEquals(ReadoutMode.HDR, restored.current)
    }

    @Test fun documentedHdrModelsAdvertiseHdrWithSdkFlag() {
        for (model in listOf(
            "ATR585M", "ATR585C", "AE676M", "AE676C", "ATR2110C",
            "G3M662M", "G3M662C", "G3M664C"
        )) {
            val modes = ToupcamReadoutModes.advertised(model, true, true, false)
            assertTrue("$model should expose HDR", ReadoutMode.HDR in modes)
        }
    }

    @Test fun documentedHdrModelsDoNotDependOnTheSdkFlag() {
        val modes = ToupcamReadoutModes.advertised("ATR585M", true, false, false)
        assertTrue(ReadoutMode.HDR in modes)
    }

    @Test fun modelSuffixesAndUsbAliasesKeepHdr() {
        for (model in listOf(
            "ATR585M(USB2.0)",
            "ATR585M-GPS",
            "ATR585M Pro-GPS",
            "ATR585C-plus",
            "AE676M(USB2.0)",
            "AE676C",
            "G3M662M(USB2.0)",
            "G3M662C",
            "G3M664C"
        )) {
            assertTrue(model, ToupTekDevices.supportsHdr(model))
        }
    }

    @Test fun undocumentedAndExplicitlyUnsupportedModelsDoNotGainHdr() {
        for (model in listOf(
            "ATR2600M",
            "ATR2600C",
            "ATR533M",
            "ATR533C",
            "G3M678M",
            "G3M678C",
            null
        )) {
            assertFalse(model.orEmpty(), ToupTekDevices.supportsHdr(model))
        }
    }

    @Test fun staleHdrReadbackFallsBackInsteadOfLeavingAnUnavailableSelection() {
        val modes = listOf(ReadoutMode.LCG, ReadoutMode.HCG)
        assertEquals(ReadoutMode.LCG, ToupcamReadoutModes.current(2, modes))
    }

    @Test fun camerasWithoutConversionGainStayInNormalMode() {
        val modes = ToupcamReadoutModes.advertised(
            modelName = "G3M678M",
            hasCg = false,
            hasCgHdr = false,
            hasLowNoise = false
        )
        assertEquals(listOf(ReadoutMode.NORMAL), modes)
        assertEquals(ReadoutMode.NORMAL, ToupcamReadoutModes.current(0, modes))
    }
}
