package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.CameraScienceLayouts
import com.indigo.mobileobservatory.camera.Roi
import org.junit.Assert.*
import org.junit.Test

class LiveStackAdmissionTest {
    private fun key(width: Int = 3536, height: Int = 3536) = StackSessionKey("camera", width, height,
        PixelFormat.BAYER_RG16, Roi(0, 0, width, height), 1, 1, 1_000_000.0, 100f, "none", "raw")

    @Test fun metadataAcceptsRealLargeSensorsWithoutRequiringCalibrationFrames() {
        for ((w, h) in listOf(3536 to 3536, 6248 to 4176)) {
            val geometry = CalibrationGeometry.from(w, h, PixelFormat.BAYER_RG16, PixelTraits())
            CalibrationSelection().validate(geometry, CalibrationCapture())
            assertEquals(w * h, geometry.sampleCount)
        }
        assertThrows(IllegalArgumentException::class.java) { CalibrationGeometry(Int.MAX_VALUE, Int.MAX_VALUE, "RGB", 16) }
    }

    @Test fun fullSensorAdmissionExplainsSizeBeforeAnyCalibrationAndDisplayBinningCannotBypassIt() {
        val input = key()
        assertEquals("processing_size", LiveStackAdmission.issue(input, 256L shl 20))
        assertEquals("processing_size", LiveStackAdmission.issue(input.copy(softwareBin = 4), 256L shl 20))
    }

    @Test fun adviceOnlyUsesAdvertisedHardwareBinsAndChecksTheirFullFrameBudget() {
        val bin = LiveStackAdmission.recommendHardwareBin(key(), 3536, 3536, listOf(1, 2, 3, 4), 192L shl 20)
        assertEquals(4, bin)
        val reduced = key(884, 884).copy(hardwareBin = 4)
        assertNull(LiveStackAdmission.issue(reduced, 192L shl 20))
        assertNull(LiveStackAdmission.recommendHardwareBin(key(), 3536, 3536, listOf(1, 2), 192L shl 20))
        assertNull(LiveStackAdmission.recommendHardwareBin(key(), 3536, 3536, listOf(1, 2, 3, 4), 32L shl 20))
    }

    @Test fun byteBudgetIncludesCalibrationAndDemosaicWorkingMemory() {
        val input = key(640, 480)
        val needed = LiveStackAdmission.workingBytes(input, 1234, true)
        assertNull(LiveStackAdmission.issue(input, needed, 1234, true))
        assertEquals("memory_budget", LiveStackAdmission.issue(input, needed - 1, 1234, true))
        assertTrue(needed > LiveStackAdmission.workingBytes(input.copy(demosaicAlgorithm = DemosaicAlgorithm.BILINEAR), 0, false))
    }

    @Test fun loggedUranusFrameNeedsMoreThanTwoTimesBinningUnderTheCurrentBudget() {
        val traits = requireNotNull(CameraScienceLayouts.fullContainer(PixelFormat.BAYER_RG16))
        val input = key(3856, 2180).copy(pixelTraits = traits)
        assertEquals("processing_size", LiveStackAdmission.issue(input, 256L shl 20))
        val bin2 = key(1928, 1090).copy(hardwareBin = 2, pixelTraits = traits)
        assertEquals("bayer_hardware_bin", LiveStackAdmission.issue(bin2, 256L shl 20))
        assertTrue(LiveStackAdmission.workingBytes(bin2, 0, false) > 256L shl 20)
        // Actual Player One Auto reports a sensor CFA, not a verified post-bin CFA.
        // Do not invent a safe even-bin colour layout merely because its dimensions fit.
        assertNull(LiveStackAdmission.recommendHardwareBin(input, 3856, 2180, listOf(1, 2), 256L shl 20))
        assertNull(LiveStackAdmission.recommendHardwareBin(input, 3856, 2180, listOf(1, 2, 4), 256L shl 20))
        val verifiedOutput = input.copy(pixelTraits = traits.copy(bayerPatternAtOutput = true))
        assertEquals(4, LiveStackAdmission.recommendHardwareBin(verifiedOutput, 3856, 2180, listOf(1, 2, 4), 256L shl 20))
        assertNull(LiveStackAdmission.issue(key(1024, 576).copy(pixelTraits = traits), 128L shl 20))
    }

    @Test fun oversizedMasterBuildRejectsBeforeReadingOrAllocatingPixels() {
        var reads = 0
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CalibrationBuilder.build(CalibrationType.BIAS, CalibrationGeometry(3536, 3536, "MONO", 16),
                CalibrationCapture(), 1, { reads++; error("Unexpected source read") }, allowUnknownCapture = true)
        }
        assertEquals("memory_budget", failure.message)
        assertEquals(0, reads)
    }
}
