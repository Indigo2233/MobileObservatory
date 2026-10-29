package com.indigo.mobileobservatory.camera.toupcam

import com.indigo.mobileobservatory.camera.Roi
import org.junit.Assert.*
import org.junit.Test

class ToupcamHardwareBinningTest {
    private class Sdk : ToupcamHardwareBinning.Port {
        var values = listOf("1x1", "2x2", "4x4")
        var methods = listOf("Skip", "Add", "Average")
        var selected = ToupcamHardwareBinning.Mode("1x1", "Average")
        var digital = 1
        var crop = Roi(80, 60, 320, 240)
        var ignoreFour = false
        var failRestore = false
        var invalidSize = false
        val writes = mutableListOf<String>()
        override fun values() = values
        override fun methods() = methods
        override fun mode() = selected
        override fun setMode(mode: ToupcamHardwareBinning.Mode): Boolean {
            writes += "sensor:${mode.value}:${mode.method}"
            if (failRestore && mode.value == "1x1") return false
            if (!(ignoreFour && mode.value == "4x4")) selected = mode
            return true
        }
        override fun digitalBin() = digital
        override fun setDigitalBin(value: Int): Boolean { writes += "digital:$value"; digital = value; return true }
        override fun roi() = crop
        override fun setRoi(roi: Roi): Boolean { writes += "roi:$roi"; crop = roi; return true }
        override fun finalSize(): Pair<Int, Int> {
            val factor = selected.value.substringBefore('x').toInt() * digital
            return if (invalidSize) 640 to 480 else 640 / factor to 480 / factor
        }
    }
    private fun controller(sdk: Sdk) = ToupcamHardwareBinning(sdk, 640, 480)

    @Test fun discoveryReadsAdvertisedSymmetricModesWithoutProbingWrites() {
        val sdk = Sdk().apply { values = listOf("1x1", "2x2", "2x4", "4x4", "999999999999x999999999999") }
        assertEquals(listOf(1, 2, 4), controller(sdk).supportedBins)
        assertTrue(sdk.writes.isEmpty())
    }

    @Test fun skipOnlyOrMissingRestoreModeLeavesBinningToTheApplication() {
        val sdk = Sdk().apply { methods = listOf("Skip") }
        assertEquals(listOf(1), controller(sdk).supportedBins)
        sdk.methods = listOf("Average"); sdk.values = listOf("2x2", "4x4")
        assertEquals(listOf(1), controller(sdk).supportedBins)
        assertTrue(sdk.writes.isEmpty())
    }

    @Test fun sensorBinningReplacesDigitalBinningAndUsesSdkFinalGeometry() {
        val sdk = Sdk().apply { digital = 2 }
        val result = controller(sdk).apply(4)!!
        assertEquals(ToupcamHardwareBinning.Mode("4x4", "Average"), sdk.selected)
        assertEquals(1, sdk.digital)
        assertEquals(160, result.width); assertEquals(120, result.height)
        assertTrue(sdk.writes.indexOf("digital:1") < sdk.writes.indexOf("sensor:4x4:Average"))
        assertEquals(Roi(0, 0, 0, 0), sdk.crop)
    }

    @Test fun addIsUsedWhenTheSensorDoesNotOfferAverage() {
        val sdk = Sdk().apply { methods = listOf("Skip", "Add") }
        assertNotNull(controller(sdk).apply(2))
        assertEquals("Add", sdk.selected.method)
    }

    @Test fun acceptedWriteWithDifferentReadbackRestoresOriginalModeDigitalBinAndCrop() {
        val sdk = Sdk().apply { ignoreFour = true; digital = 2 }
        val crop = sdk.crop
        assertNull(controller(sdk).apply(4))
        assertEquals("1x1", sdk.selected.value)
        assertEquals(2, sdk.digital)
        assertEquals(crop, sdk.crop)
        assertNotNull(controller(sdk).apply(2)) // a smaller native divisor is still usable
    }

    @Test fun incompatibleFrameDimensionsRejectTheModeAndRestoreTheCrop() {
        val sdk = Sdk().apply { invalidSize = true }
        val crop = sdk.crop
        assertNull(controller(sdk).apply(2))
        assertEquals("1x1", sdk.selected.value)
        assertEquals(crop, sdk.crop)
    }

    @Test fun failedRollbackRequiresAcquisitionToRemainStopped() {
        val sdk = Sdk().apply { ignoreFour = true; failRestore = true }
        try { controller(sdk).apply(4); fail("Unknown native geometry must not enter software fallback") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("reconnect")) }
    }

    @Test fun openingAnAlreadyUnbinnedCameraDoesNotWriteBinning() {
        val sdk = Sdk()
        assertEquals(1, controller(sdk).initialize()!!.bin)
        assertTrue(sdk.writes.isEmpty())
    }
}
