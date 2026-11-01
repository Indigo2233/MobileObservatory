package com.indigo.mobileobservatory.camera.playerone

import com.indigo.mobileobservatory.camera.AppBinPlan
import com.indigo.mobileobservatory.camera.AppBinning
import com.indigo.mobileobservatory.camera.CameraBinningCapable
import com.indigo.mobileobservatory.camera.Roi
import com.playeroneastronomy.camera.PoaImageFormat
import org.junit.Assert.*
import org.junit.Test

class PlayerOneBinningTest {
    private class Sdk : PlayerOneBinning.Port {
        var imageBin = 1
        var imageRoi = Roi(80, 60, 640, 480)
        var imageFormat = PoaImageFormat.RAW16
        var hardware: PlayerOneBinning.Control? = PlayerOneBinning.Control(true, true)
        var mono: PlayerOneBinning.Control? = PlayerOneBinning.Control(true, true)
        var ignoreBin = false
        var ignoreMono = false
        var resetControlsOnBin = false
        var resetMonoOnHardware = false
        var changeFormatOnBin = false
        var wrongBinnedSize = false
        var failRestore = false
        var failRead = false
        val writes = mutableListOf<String>()

        override fun read(): PlayerOneBinning.State {
            check(!failRead) { "SDK read failed" }
            return PlayerOneBinning.State(imageBin, imageRoi, imageFormat, hardware, mono)
        }
        override fun setBin(bin: Int) {
            writes += "bin:$bin"
            if (!ignoreBin || bin == 1) imageBin = bin
            if (bin > 1 && resetControlsOnBin) {
                hardware = hardware?.copy(value = true)
                mono = mono?.copy(value = true)
            }
            if (bin > 1 && changeFormatOnBin) imageFormat = PoaImageFormat.RAW8
        }
        override fun setFormat(format: PoaImageFormat) {
            writes += "format:$format"
            imageFormat = format
        }
        override fun setRoi(roi: Roi) {
            writes += "roi:$roi"
            check(!(failRestore && imageBin == 1)) { "Cannot restore ROI" }
            imageRoi = if (wrongBinnedSize && imageBin > 1) roi.copy(width = roi.width - 4) else roi
        }
        override fun setHardware(enabled: Boolean) {
            writes += "hardware:$enabled"
            hardware = hardware?.copy(value = enabled)
            if (resetMonoOnHardware) mono = mono?.copy(value = true)
        }
        override fun setMono(enabled: Boolean) {
            writes += "mono:$enabled"
            if (!ignoreMono) mono = mono?.copy(value = enabled)
        }
    }

    private fun controller(sdk: Sdk, colour: Boolean = true) =
        PlayerOneBinning(sdk, 3856, 2180, colour, listOf(1, 2, 3, 4))

    @Test fun colourBin2DeliversQuarterSizeRaw16WithBothMixingControlsDisabled() {
        val sdk = Sdk()
        val control = controller(sdk)
        val result = control.apply(2, PoaImageFormat.RAW16)!!
        assertEquals(2, result.bin)
        assertEquals(Roi(0, 0, 1928, 1090), result.roi)
        assertEquals(PoaImageFormat.RAW16, result.format)
        assertEquals(false, result.hardware?.value)
        assertEquals(false, result.mono?.value)
        assertTrue(control.outputCfaConfirmed)
        assertEquals(3856L * 2180 * 2 / 4, result.roi.width.toLong() * result.roi.height * 2)
        assertTrue(sdk.writes.containsAll(listOf("hardware:false", "mono:false")))
    }

    @Test fun controlsAreCheckedAfterBinAndAfterHardwareWrites() {
        val sdk = Sdk().apply {
            hardware = hardware!!.copy(value = false)
            mono = mono!!.copy(value = false)
            resetControlsOnBin = true
            resetMonoOnHardware = true
        }
        val result = controller(sdk).apply(2, PoaImageFormat.RAW16)!!
        assertEquals(false, result.hardware?.value)
        assertEquals(false, result.mono?.value)
        assertTrue(sdk.writes.indexOf("bin:2") < sdk.writes.indexOf("hardware:false"))
        assertTrue(sdk.writes.indexOf("hardware:false") < sdk.writes.indexOf("mono:false"))
    }

    @Test fun readonlyDisabledControlsAndCamerasWithoutSensorBinningUseSdkColourBin() {
        val sdk = Sdk().apply {
            hardware = PlayerOneBinning.Control(false, false)
            mono = PlayerOneBinning.Control(false, false)
        }
        val control = controller(sdk)
        assertNotNull(control.apply(2, PoaImageFormat.RAW16))
        assertTrue(control.outputCfaConfirmed)
        assertFalse(sdk.writes.any { it.startsWith("hardware:") || it.startsWith("mono:") })
    }

    @Test fun unavailableOrReadonlyEnabledControlsRejectBeforeWritingTheCamera() {
        val unsafe = listOf(
            null to PlayerOneBinning.Control(false, true),
            PlayerOneBinning.Control(false, true) to null,
            PlayerOneBinning.Control(true, false) to PlayerOneBinning.Control(false, true),
            PlayerOneBinning.Control(false, true) to PlayerOneBinning.Control(true, false)
        )
        for ((hardware, mono) in unsafe) {
            val sdk = Sdk().apply { this.hardware = hardware; this.mono = mono }
            val control = controller(sdk)
            assertNull(control.apply(2, PoaImageFormat.RAW16))
            assertTrue(sdk.writes.isEmpty())
            assertFalse(control.outputCfaConfirmed)
            assertEquals(1, control.state?.bin)
        }
    }

    @Test fun ignoredMonoWriteRestoresTheCompleteOriginalOutput() {
        val sdk = Sdk().apply { ignoreMono = true }
        val before = sdk.read()
        val control = controller(sdk)
        assertNull(control.apply(2, PoaImageFormat.RAW16))
        assertEquals(before, sdk.read())
        assertEquals(before, control.state)
        assertFalse(control.outputCfaConfirmed)
    }

    @Test fun binFormatAndGeometryReadbackFailuresRestoreTheOriginalState() {
        val failures: List<Sdk.() -> Unit> = listOf(
            { ignoreBin = true },
            { changeFormatOnBin = true },
            { wrongBinnedSize = true }
        )
        for (failure in failures) {
            val sdk = Sdk().apply(failure)
            val before = sdk.read()
            val control = controller(sdk)
            assertNull(control.apply(2, PoaImageFormat.RAW16))
            assertEquals(before, sdk.read())
            assertEquals(before, control.state)
        }
    }

    @Test fun failedRestorationLeavesNoVerifiedOutputForCaptureOrSoftwareFallback() {
        val sdk = Sdk().apply { wrongBinnedSize = true; failRestore = true }
        val control = controller(sdk)
        assertThrows(IllegalStateException::class.java) { control.apply(2, PoaImageFormat.RAW16) }
        assertNull(control.state)
        assertFalse(control.outputCfaConfirmed)
        assertThrows(IllegalStateException::class.java) { control.verify() }
    }

    @Test fun colourRoiKeepsAnEvenDeliveredOriginAndVerifiesActualSdkGeometry() {
        val sdk = Sdk()
        val control = controller(sdk)
        val result = control.apply(2, PoaImageFormat.RAW16, Roi(81, 61, 641, 481))!!
        assertEquals(Roi(80, 60, 640, 480), result.roi)
        assertEquals(result, control.verify())
        assertTrue(control.outputCfaConfirmed)
    }

    @Test fun allAdvertisedColourFactorsKeepRawDepthAndDisableMixedColourModes() {
        for (bin in listOf(2, 3, 4)) for (format in listOf(PoaImageFormat.RAW8, PoaImageFormat.RAW16)) {
            val sdk = Sdk().apply { imageFormat = format }
            val control = controller(sdk)
            val result = control.apply(bin, format)!!
            assertEquals(format, result.format)
            assertEquals(bin, result.bin)
            assertEquals(false, result.mono?.value)
            assertEquals(false, result.hardware?.value)
            assertTrue(control.outputCfaConfirmed)
        }
    }

    @Test fun captureResumeOnlyVerifiesAndNeverReenablesSensorHardwareBinning() {
        val sdk = Sdk()
        val control = controller(sdk)
        val result = control.apply(2, PoaImageFormat.RAW16)!!
        sdk.writes.clear()
        assertEquals(result, control.verify())
        assertTrue(sdk.writes.isEmpty())
        sdk.hardware = sdk.hardware!!.copy(value = true)
        assertThrows(IllegalStateException::class.java) { control.verify() }
        assertNull(control.state)
        assertFalse(control.outputCfaConfirmed)
        assertTrue(sdk.writes.isEmpty())
    }

    @Test fun changedMonoFlagOrFormatCannotReuseAnEarlierCfaConfirmation() {
        val sdk = Sdk()
        val control = controller(sdk)
        control.apply(2, PoaImageFormat.RAW16)
        sdk.mono = sdk.mono!!.copy(value = true)
        assertThrows(IllegalStateException::class.java) { control.verify() }
        assertNull(control.state)
        control.apply(2, PoaImageFormat.RAW16)
        sdk.imageFormat = PoaImageFormat.RAW8
        assertThrows(IllegalStateException::class.java) { control.apply(2, PoaImageFormat.RAW16) }
        assertNull(control.state)
        assertFalse(control.outputCfaConfirmed)
    }

    @Test fun unreadableGeometryInvalidatesEarlierConfirmation() {
        val sdk = Sdk()
        val control = controller(sdk)
        control.apply(2, PoaImageFormat.RAW16)
        sdk.failRead = true
        assertThrows(IllegalStateException::class.java) { control.apply(2, PoaImageFormat.RAW16) }
        assertNull(control.state)
    }

    @Test fun monochromeOutputRetainsTheSensorHardwarePreference() {
        val sdk = Sdk().apply { imageFormat = PoaImageFormat.MONO8; hardware = hardware!!.copy(value = false) }
        val control = controller(sdk)
        assertEquals(true, control.apply(2, PoaImageFormat.MONO8)?.hardware?.value)
        assertFalse(control.outputCfaConfirmed)
        sdk.imageFormat = PoaImageFormat.RAW16
        assertEquals(false, control.apply(2, PoaImageFormat.RAW16)?.hardware?.value)
        assertTrue(control.outputCfaConfirmed)
    }

    @Test fun applicationKeepsConfirmedSdkBinAndFallsBackWhenColourCannotBeConfirmed() {
        for (available in listOf(true, false)) {
            val sdk = Sdk().apply { if (!available) mono = null }
            val control = controller(sdk)
            control.apply(1, PoaImageFormat.RAW16)
            val camera = object : CameraBinningCapable {
                override val supportedHardwareBins = listOf(1, 2, 3, 4)
                override val currentHardwareBin get() = control.state?.bin ?: sdk.imageBin
                override val binningStateVerified get() = control.state != null
                override fun setHardwareBin(bin: Int) = control.apply(bin, PoaImageFormat.RAW16) != null
            }
            val plan = AppBinning.apply(2, camera) { it == 1 || control.outputCfaConfirmed }
            assertEquals(if (available) AppBinPlan(2, 2, 1) else AppBinPlan(2, 1, 2), plan)
            assertEquals(if (available) 1928 else 3856, control.state!!.roi.width)
            assertEquals(if (available) 1090 else 2180, control.state!!.roi.height)
        }
    }
}
