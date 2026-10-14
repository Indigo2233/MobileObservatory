package com.indigo.mobileobservatory.camera

import com.indigo.mobileobservatory.recording.FITSWriter
import com.indigo.mobileobservatory.recording.Mp4YuvConverter
import com.indigo.mobileobservatory.recording.RawRecordingNormalizer
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SERWriter
import com.indigo.mobileobservatory.recording.PSERWriter
import com.indigo.mobileobservatory.stacking.*
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CameraCfaLayoutTest {
    private val w = 16; private val h = 16
    private fun traits(bits: Int, order: ByteOrder = ByteOrder.LITTLE_ENDIAN, alignment: SampleAlignment = SampleAlignment.RIGHT) =
        PixelTraits(validBits = bits, byteOrder = order, alignment = alignment, roiX = 1, roiY = 1,
            bayerPatternAtOutput = false)
    private fun mosaic(selection: SerCfaInterpretation, bits: Int, order: ByteOrder = ByteOrder.LITTLE_ENDIAN,
                        alignment: SampleAlignment = SampleAlignment.RIGHT): FrameData {
        val format = PixelFormat.valueOf("MONO$bits")
        val data = ByteArray(w * h * format.bytesPerPixel)
        val redX = if (selection in listOf(SerCfaInterpretation.RGGB, SerCfaInterpretation.GBRG)) 0 else 1
        val redY = if (selection in listOf(SerCfaInterpretation.RGGB, SerCfaInterpretation.GRBG)) 0 else 1
        val maximum = (1 shl bits) - 1
        for (y in 0 until h) for (x in 0 until w) {
            val signal = when { x % 2 == redX && y % 2 == redY -> .75; x % 2 != redX && y % 2 != redY -> .15; else -> .4 }
            val canonical = (signal * maximum).toInt()
            val value = canonical shl (if (alignment == SampleAlignment.LEFT) 16 - bits else 0)
            val p = (y * w + x) * format.bytesPerPixel
            data[p] = (if (bits == 8 || order == ByteOrder.LITTLE_ENDIAN) value else value ushr 8).toByte()
            if (bits != 8) data[p + 1] = (if (order == ByteOrder.LITTLE_ENDIAN) value ushr 8 else value).toByte()
        }
        return FrameData(data, w, h, format, 1, 100, captureGeneration = 9)
    }

    @Test fun manualPatternsKeepNativeDepthAndDecodeTheChosenOutputPhaseWithoutApplyingRoiTwice() {
        for (bits in listOf(8, 10, 12, 14, 16)) for (selection in listOf(SerCfaInterpretation.RGGB,
            SerCfaInterpretation.BGGR, SerCfaInterpretation.GRBG, SerCfaInterpretation.GBRG)) {
            val source = mosaic(selection, bits)
            val effective = CameraCfaLayout.interpret(source, selection)
            val layout = CameraCfaLayout.effectiveTraits(traits(bits), selection)!!
            assertSame(source.data, effective.data)
            assertEquals(bits, effective.pixelFormat.nativeBits)
            assertEquals(source.bytesPerPixel, effective.bytesPerPixel)
            assertTrue(layout.bayerPatternAtOutput)
            assertEquals(1, layout.roiX); assertEquals(1, layout.roiY)
            val decoded = LinearPixelDecoder.decode(effective, layout)
            val p = (8 * w + 8) * 3
            val tolerance = 1.0 / ((1 shl bits) - 1) + 1e-6
            assertEquals(.75, decoded.pixels[p].toDouble(), tolerance)
            assertEquals(.4, decoded.pixels[p + 1].toDouble(), tolerance)
            assertEquals(.15, decoded.pixels[p + 2].toDouble(), tolerance)
        }
    }

    @Test fun automaticPreservesSdkTraitsAndMonoSelectionSuppressesDemosaicWithoutChangingSamples() {
        val source = mosaic(SerCfaInterpretation.BGGR, 12)
        val sdk = traits(12)
        assertSame(source, CameraCfaLayout.interpret(source, SerCfaInterpretation.AUTO))
        assertSame(sdk, CameraCfaLayout.effectiveTraits(sdk, SerCfaInterpretation.AUTO))
        val bayer = CameraCfaLayout.interpret(source, SerCfaInterpretation.BGGR)
        val mono = CameraCfaLayout.interpret(bayer, SerCfaInterpretation.MONO)
        assertSame(source.data, mono.data)
        assertEquals(PixelFormat.MONO12, mono.pixelFormat)
        assertEquals(1, LinearPixelDecoder.decode(mono, sdk).channels)
        assertNull(CameraCfaLayout.effectiveTraits(null, SerCfaInterpretation.RGGB))
    }

    @Test fun canonicalStillPreservesEveryHighBitCodeAndSourceOwnershipForBothByteOrders() {
        for (bits in listOf(10, 12, 14)) for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val raw = mosaic(SerCfaInterpretation.GRBG, bits, order, SampleAlignment.LEFT)
            val originalBytes = raw.data.copyOf()
            val frame = CameraCfaLayout.interpret(raw, SerCfaInterpretation.GRBG)
            val sdk = CameraCfaLayout.effectiveTraits(traits(bits, order, SampleAlignment.LEFT), SerCfaInterpretation.GRBG)!!
            val expected = LinearPixelDecoder.decode(frame, sdk)
            val snapshot = CameraCfaLayout.ownedSnapshot(frame, sdk)
            val capturedTraits = requireNotNull(snapshot.traits)
            assertNotSame(raw.data, snapshot.frame.data)
            assertArrayEquals(originalBytes, raw.data)
            assertEquals(bits, snapshot.frame.pixelFormat.nativeBits)
            assertEquals(ByteOrder.LITTLE_ENDIAN, capturedTraits.byteOrder)
            assertEquals(SampleAlignment.RIGHT, capturedTraits.alignment)
            assertArrayEquals(expected.pixels, LinearPixelDecoder.decode(snapshot.frame, capturedTraits).pixels, 0f)
        }
    }

    @Test fun previewVideoAndScientificInterpretationsAgreeOnManualColor() {
        val selection = SerCfaInterpretation.BGGR
        val source = mosaic(selection, 12, ByteOrder.BIG_ENDIAN, SampleAlignment.LEFT)
        val frame = CameraCfaLayout.interpret(source, selection)
        val sdk = CameraCfaLayout.effectiveTraits(traits(12, ByteOrder.BIG_ENDIAN, SampleAlignment.LEFT), selection)!!
        val display = CameraCfaLayout.displayFrame(frame, sdk)
        assertSame(frame, display)
        val canonical = CameraCfaLayout.ownedSnapshot(frame, sdk)
        val liveDecoded = LinearPixelDecoder.decode(frame, sdk)
        assertArrayEquals(liveDecoded.pixels, LinearPixelDecoder.decode(canonical.frame, canonical.traits!!).pixels, 0f)
        val rawVideo = Mp4YuvConverter().convert(display, w, h, sdk)
        val canonicalVideo = Mp4YuvConverter().convert(canonical.frame, w, h, canonical.traits)
        assertArrayEquals(rawVideo.y, canonicalVideo.y)
        assertArrayEquals(rawVideo.uv, canonicalVideo.uv)
    }

    @Test fun automaticOddRoiGetsOneOutputPhaseAdjustmentAndUnknownEvenBinDoesNotInventColor() {
        val raw = FrameData(ByteArray(w*h*2), w, h, PixelFormat.BAYER_RG12, 1, 100)
        assertEquals(PixelFormat.BAYER_BG12, CameraCfaLayout.displayFrame(raw, traits(12)).pixelFormat)
        assertEquals(PixelFormat.BAYER_RG12, CameraCfaLayout.displayFrame(raw, traits(12).copy(roiX = 2, roiY = 2)).pixelFormat)
        assertEquals(PixelFormat.MONO12, CameraCfaLayout.displayFrame(raw, traits(12).copy(binning = 2)).pixelFormat)
        val manual = CameraCfaLayout.effectiveTraits(traits(12).copy(binning = 2), SerCfaInterpretation.RGGB)!!
        assertEquals(PixelFormat.BAYER_RG12, CameraCfaLayout.displayFrame(raw, manual).pixelFormat)
    }

    @Test fun layoutChangesRestoreAutomaticForEvenAndOddRoiAndBinChanges() {
        val layout = "cameraA|MONO12|${Roi(0,0,16,16)}|bin1"
        assertEquals(SerCfaInterpretation.BGGR, CameraCfaLayout.boundSelection(SerCfaInterpretation.BGGR, layout, layout))
        for (changed in listOf("cameraA|MONO12|${Roi(1,0,16,16)}|bin1", "cameraA|MONO12|${Roi(2,0,16,16)}|bin1",
            "cameraA|MONO12|${Roi(0,0,16,16)}|bin2", "cameraB|MONO12|${Roi(0,0,16,16)}|bin1")) {
            assertEquals(SerCfaInterpretation.AUTO, CameraCfaLayout.boundSelection(SerCfaInterpretation.BGGR, layout, changed))
        }
    }

    @Test fun highBitBigEndianSoftwareBinningRetainsCfaColorsAndSameBufferIsRecycledOnlyByItsOwner() {
        val raw = mosaic(SerCfaInterpretation.GBRG, 14, ByteOrder.BIG_ENDIAN, SampleAlignment.LEFT)
        val frame = CameraCfaLayout.interpret(raw, SerCfaInterpretation.GBRG)
        val sdk = CameraCfaLayout.effectiveTraits(traits(14, ByteOrder.BIG_ENDIAN, SampleAlignment.LEFT), SerCfaInterpretation.GBRG)!!
        val binner = SoftwareBinning()
        assertSame(raw.data, binner.apply(frame, sdk).data)
        assertFalse(binner.release(frame.data))
        binner.factor = 2
        val binned = binner.apply(frame, sdk)
        assertNotSame(frame.data, binned.data)
        assertEquals(14, binned.pixelFormat.nativeBits)
        val result = LinearPixelDecoder.decode(binned, sdk)
        val p = (4 * binned.width + 4) * 3
        assertEquals(.75, result.pixels[p].toDouble(), 1e-4)
        assertEquals(.4, result.pixels[p + 1].toDouble(), 1e-4)
        assertEquals(.15, result.pixels[p + 2].toDouble(), 1e-4)
        assertTrue(binner.release(binned.data)); assertFalse(binner.release(binned.data))
        assertFalse(binner.release(raw.data))
    }

    @Test fun serializedSerPserAndFitsDeclareTheSameCfaAndPrecisionAsLiveDecoding() {
        val directory = Files.createTempDirectory("camera-cfa-").toFile()
        try {
            val source = CameraCfaLayout.interpret(mosaic(SerCfaInterpretation.BGGR, 12), SerCfaInterpretation.BGGR)
            val sdk = CameraCfaLayout.effectiveTraits(traits(12), SerCfaInterpretation.BGGR)!!
            val canonical = RawRecordingNormalizer.normalize(source, sdk)
            val ser = java.io.File(directory, "cfa.ser")
            SERWriter(ser).apply { open(w,h,canonical.pixelFormat); writeFrame(canonical); close() }
            val pser = java.io.File(directory, "cfa.pser")
            PSERWriter(pser).apply { open(w,h,canonical.pixelFormat); writeFrame(canonical); close() }
            for (file in listOf(ser, pser)) SERReader(file).use { reader ->
                assertTrue(reader.open()); reader.sampleAlignment = SampleAlignment.RIGHT
                assertEquals(11, reader.header!!.colorId)
                assertEquals(12, reader.header!!.pixelDepth)
                val stored = reader.readLinearFrame(0)!!
                assertArrayEquals(LinearPixelDecoder.decode(canonical, PixelTraits(alignment=SampleAlignment.RIGHT,validBits=12)).pixels, stored.pixels, 0f)
            }
            val fits = java.io.File(directory, "cfa.fits")
            FITSWriter().write(fits, canonical, .01f, 1f, configuredFormat = canonical.pixelFormat)
            val header = fits.readBytes().copyOfRange(0,2880).toString(Charsets.US_ASCII)
            assertTrue(header.contains("BAYERPAT= 'BGGR'"))
            assertTrue(Regex("VALIDBIT=\\s+12").containsMatchIn(header))
        } finally { directory.deleteRecursively() }
    }

    @Test fun rgbAndOccupiedCaptureCannotBeRelabeledAndStackResetNeedsExplicitConsent() {
        val rgb = FrameData(ByteArray(w*h*3),w,h,PixelFormat.RGB24,1,100)
        assertSame(rgb, CameraCfaLayout.interpret(rgb, SerCfaInterpretation.AUTO))
        try { CameraCfaLayout.interpret(rgb, SerCfaInterpretation.RGGB); fail("RGB is not raw") } catch (_: IllegalArgumentException) { }
        val base = CameraCfaChangeState(true, PixelFormat.MONO12)
        assertEquals("recording", CameraCfaChangePolicy.issue(base.copy(recording=true), SerCfaInterpretation.RGGB))
        assertEquals("calibration", CameraCfaChangePolicy.issue(base.copy(calibration=true), SerCfaInterpretation.RGGB))
        assertEquals("snapshot", CameraCfaChangePolicy.issue(base.copy(snapshot=true), SerCfaInterpretation.RGGB))
        assertEquals("settings", CameraCfaChangePolicy.issue(base.copy(settings=true), SerCfaInterpretation.RGGB))
        assertEquals("reset_stack", CameraCfaChangePolicy.issue(base.copy(stackExists=true), SerCfaInterpretation.RGGB))
        assertNull(CameraCfaChangePolicy.issue(base.copy(stackExists=true), SerCfaInterpretation.RGGB, resetConfirmed=true))
    }

    @Test fun localAcquisitionEpochRejectsOldCallbacksEvenWithoutSdkTokens() {
        val epoch = CameraPreviewEpoch()
        val original = epoch.nextRun()
        assertTrue(epoch.isCurrent(original))
        epoch.invalidate()
        assertFalse(epoch.isCurrent(original))
        val fresh = epoch.nextRun()
        assertFalse(epoch.isCurrent(original)); assertTrue(epoch.isCurrent(fresh))
    }
}
