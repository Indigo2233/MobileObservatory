package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LiveFrameDecoderTest {
    private fun frame(format: PixelFormat, width: Int = 149, height: Int = 137,
                      order: ByteOrder = ByteOrder.LITTLE_ENDIAN, shift: Int = 0): FrameData {
        val bytes = ByteBuffer.allocate(width * height * format.bytesPerPixel).order(order)
        val channels = if (format.isRgb) 3 else 1
        for (y in 0 until height) for (x in 0 until width) for (c in 0 until channels) {
            val value = (300 + (x * 19 + y * 23 + c * 307) % 3000) shl shift
            bytes.putShort(value.toShort())
        }
        return FrameData(bytes.array(), width, height, format, 1, 0)
    }

    private fun key(frame: FrameData, bin: Int, algorithm: DemosaicAlgorithm,
                    traits: PixelTraits = PixelTraits(alignment = SampleAlignment.RIGHT, bayerPatternAtOutput = false)) =
        StackSessionKey("camera", frame.width, frame.height, frame.pixelFormat,
            Roi(1, 3, frame.width, frame.height), 1, bin, 5_000_000.0, 100f, "none", "raw",
            pixelTraits = traits, demosaicAlgorithm = algorithm)

    @Test fun tiledLiveMatchesWholeFrameCalibrationCfaAndBinAcrossSeamsAndOddEdges() {
        for (format in listOf(PixelFormat.BAYER_RG16, PixelFormat.BAYER_GR16,
                PixelFormat.BAYER_GB16, PixelFormat.BAYER_BG16)) {
            val input = frame(format)
            val original = input.data.copyOf()
            for (outputCfa in listOf(false, true)) {
                val base = key(input, 1, DemosaicAlgorithm.DDFAPD).copy(
                    hardwareBin = if (outputCfa) 2 else 1,
                    pixelTraits = PixelTraits(alignment = SampleAlignment.RIGHT, bayerPatternAtOutput = outputCfa))
                val traits = base.pixelTraits.copy(roiX = base.roi.x, roiY = base.roi.y, binning = base.hardwareBin)
                val geometry = CalibrationGeometry.from(input.width, input.height, format, traits)
                fun master(type: CalibrationType) = CalibrationMaster(
                    CalibrationDescriptor(type.name, "", type, geometry, CalibrationCapture(), "test", 1, 0),
                    FloatArray(input.width * input.height) { p ->
                        if (type == CalibrationType.BIAS) .002f + p % 149 * .000001f else .9f + p % 137 * .001f
                    })
                val calibration = CalibrationSelection(bias = master(CalibrationType.BIAS),
                    flat = master(CalibrationType.FLAT), allowUnknownCapture = true)
                val sensor = LinearPixelDecoder.decodeSensor(input, traits)
                val calibrated = calibration.apply(sensor, geometry, CalibrationCapture())
                for (algorithm in DemosaicAlgorithm.entries) {
                    val full = LinearPixelDecoder.demosaicSensor(calibrated, format, traits, algorithm)
                    for (bin in listOf(1, 2, 4)) {
                        val decoder = LiveFrameDecoder()
                        try {
                            val actual = decoder.decode(input, base.copy(softwareBin = bin, demosaicAlgorithm = algorithm),
                                calibration, CalibrationCapture())
                            val expected = LinearPixelDecoder.bin(full, bin)
                            assertEquals(expected.width, actual.width)
                            assertEquals(expected.height, actual.height)
                            assertArrayEquals("$format outputCfa=$outputCfa $algorithm bin=$bin",
                                expected.pixels, actual.pixels, 1e-6f)
                        } finally { decoder.release() }
                    }
                }
            }
            assertArrayEquals(original, input.data)
        }
    }

    @Test fun monoAndRgbRetainByteOrderBitAlignmentAndChannelOrder() {
        for (format in listOf(PixelFormat.MONO12, PixelFormat.RGB48)) {
            val input = frame(format, order = ByteOrder.BIG_ENDIAN, shift = 4)
            val traits = PixelTraits(byteOrder = ByteOrder.BIG_ENDIAN, alignment = SampleAlignment.LEFT,
                validBits = 12, channelOrder = ChannelOrder.BGR)
            val key = key(input, 2, DemosaicAlgorithm.DDFAPD, traits)
            val expected = LinearPixelDecoder.bin(LinearPixelDecoder.decode(input, traits), 2)
            val decoder = LiveFrameDecoder()
            try {
                assertArrayEquals(expected.pixels,
                    decoder.decode(input, key, CalibrationSelection(), CalibrationCapture()).pixels, 0f)
            } finally { decoder.release() }
        }
    }

    @Test fun outputAndTileBuffersAreReusedAndCancellationCanRetry() {
        val input = frame(PixelFormat.BAYER_RG16)
        val key = key(input, 2, DemosaicAlgorithm.DDFAPD)
        val decoder = LiveFrameDecoder()
        val first = decoder.decode(input, key, CalibrationSelection(), CalibrationCapture())
        val expected = first.pixels.copyOf()
        val allocations = decoder.arrayAllocations
        assertSame(first.pixels, decoder.decode(input, key, CalibrationSelection(), CalibrationCapture()).pixels)
        assertEquals(allocations, decoder.arrayAllocations)
        var probes = 0
        assertThrows(CancellationException::class.java) {
            decoder.decode(input, key, CalibrationSelection(), CalibrationCapture()) {
                if (++probes == 12) throw CancellationException()
            }
        }
        assertArrayEquals(expected, decoder.decode(input, key, CalibrationSelection(), CalibrationCapture()).pixels, 0f)
        decoder.release()
        assertEquals(0L, decoder.retainedBytes)
    }

    @Test fun ambiguousEvenHardwareCfaStillRequiresExplicitOutputInformation() {
        val input = frame(PixelFormat.BAYER_RG16)
        val key = key(input, 1, DemosaicAlgorithm.DDFAPD).copy(hardwareBin = 2)
        val decoder = LiveFrameDecoder()
        assertEquals("bayer_hardware_bin", assertThrows(IllegalArgumentException::class.java) {
            decoder.decode(input, key, CalibrationSelection(), CalibrationCapture())
        }.message)
        assertEquals(0L, decoder.retainedBytes)
    }

    @Test fun imx585SoftwareBin2RetainsOnlyOutputAndBoundedTiles() {
        val input = frame(PixelFormat.BAYER_RG16, 3856, 2180)
        val key = key(input, 2, DemosaicAlgorithm.DDFAPD)
        val decoder = LiveFrameDecoder()
        try {
            val image = decoder.decode(input, key, CalibrationSelection(), CalibrationCapture())
            assertEquals(1928, image.width)
            assertEquals(1090, image.height)
            assertEquals(3, image.channels)
            assertTrue(decoder.retainedBytes < image.pixels.size * 4L + (4L shl 20))
            val allocations = decoder.arrayAllocations
            assertSame(image.pixels, decoder.decode(input, key, CalibrationSelection(), CalibrationCapture()).pixels)
            assertEquals(allocations, decoder.arrayAllocations)
            println("585 live decoder retainedBytes=${decoder.retainedBytes} arrays=$allocations")
        } finally { decoder.release() }
    }
}
