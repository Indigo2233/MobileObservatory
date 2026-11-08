package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCompactInputTest {
    private fun frame(format: PixelFormat, width: Int = 149, height: Int = 137): FrameData {
        val bytes = ByteBuffer.allocate(width * height * format.bytesPerPixel).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val channels = if (format.isRgb) 3 else 1
        for (y in 0 until height) for (x in 0 until width) for (c in 0 until channels) {
            bytes.putShort((2000 + (x * 174 + y * 207 + c * 131) % 20000).toShort())
        }
        return FrameData(bytes.array(), width, height, format, 1, 0)
    }

    private fun key(input: FrameData, bin: Int = 2) = StackSessionKey(
        "compact-fixture", input.width, input.height, input.pixelFormat, Roi(1, 3, input.width, input.height),
        1, bin, 5_000_000.0, 100f, "none", "raw",
        pixelTraits = PixelTraits(alignment = SampleAlignment.RIGHT, bayerPatternAtOutput = false))

    private fun compare(expected: LinearImage, actual: LinearSamples) {
        assertTrue(actual is CompactLinearImage16)
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertEquals(expected.channels, actual.channels)
        for (i in expected.pixels.indices) {
            assertTrue("$i error=${abs(expected.pixels[i].toDouble() - actual.sample(i))}",
                abs(expected.pixels[i].toDouble() - actual.sample(i)) <= actual.maximumQuantizationError + 1e-12)
        }
    }

    @Test fun productionDecoderBinsBeforePackingAcrossAllCfaAlgorithmsAndEdgeBlocks() {
        for (format in listOf(PixelFormat.BAYER_RG16, PixelFormat.BAYER_GR16,
                PixelFormat.BAYER_GB16, PixelFormat.BAYER_BG16)) {
            val input = frame(format)
            val original = input.data.copyOf()
            for (algorithm in DemosaicAlgorithm.entries) for (bin in listOf(2, 3, 4)) {
                val decoder = LiveFrameDecoder()
                try {
                    val settings = key(input, bin).copy(demosaicAlgorithm = algorithm)
                    val expected = decoder.decode(input, settings, CalibrationSelection(), CalibrationCapture())
                    val actual = decoder.decodeInput(input, settings, CalibrationSelection(), CalibrationCapture())
                    compare(expected, actual)
                    assertEquals(LiveFrameDecoder.outputStorageBytes(settings), actual.storageBytes)
                } finally { decoder.release() }
            }
            assertArrayEquals(original, input.data)
        }
    }

    @Test fun calibrationNegativesAndAboveWhiteSignalAreEncodedAfterRgbBinning() {
        val input = frame(PixelFormat.RGB48)
        val settings = key(input)
        val geometry = CalibrationGeometry.from(input.width, input.height, input.pixelFormat, settings.pixelTraits)
        fun master(type: CalibrationType, value: Float) = CalibrationMaster(
            CalibrationDescriptor(type.name, "", type, geometry, CalibrationCapture(), "fixture", 1, 0),
            FloatArray(input.width * input.height * 3) { value })
        val calibration = CalibrationSelection(bias = master(CalibrationType.BIAS, .1f),
            flat = master(CalibrationType.FLAT, .05f))
        val decoder = LiveFrameDecoder()
        try {
            val expected = decoder.decode(input, settings, calibration, CalibrationCapture())
            val actual = decoder.decodeInput(input, settings, calibration, CalibrationCapture())
            compare(expected, actual)
            assertTrue(expected.pixels.any { it < 0 })
            assertTrue(expected.pixels.any { it > 1 })
            for (i in expected.pixels.indices step 97) {
                if (expected.pixels[i] < -.01f) assertTrue(actual.sample(i) < 0)
                if (expected.pixels[i] > 1.01f) assertTrue(actual.sample(i) > 1)
            }
        } finally { decoder.release() }
    }

    @Test fun compactStorageIsReusedAndCancellationRetriesWithoutAFullFloatOutput() {
        val input = frame(PixelFormat.BAYER_RG16)
        val settings = key(input)
        val decoder = LiveFrameDecoder()
        try {
            val actual = decoder.decodeInput(input, settings, CalibrationSelection(), CalibrationCapture())
            val before = decoder.retainedBytes
            val allocations = decoder.arrayAllocations
            assertSame(actual, decoder.decodeInput(input, settings, CalibrationSelection(), CalibrationCapture()))
            assertEquals(before, decoder.retainedBytes)
            assertEquals(allocations, decoder.arrayAllocations)
            var checks = 0
            assertThrows(CancellationException::class.java) {
                decoder.decodeInput(input, settings, CalibrationSelection(), CalibrationCapture()) {
                    if (++checks == 20) throw CancellationException()
                }
            }
            val retried = decoder.decodeInput(input, settings, CalibrationSelection(), CalibrationCapture())
            assertSame(actual, retried)
            assertTrue(retried.allFinite())
            assertEquals(allocations, decoder.arrayAllocations)
        } finally { decoder.release() }
        assertEquals(0L, decoder.retainedBytes)
    }

    @Test fun confirmedSdkBinUsesCompactColourWhileUnbinnedMonoAndPlanetaryKeepFloatInputs() {
        val colour = frame(PixelFormat.BAYER_RG16)
        val decoder = LiveFrameDecoder()
        try {
            val sdk = key(colour, 1).copy(hardwareBin = 2,
                pixelTraits = PixelTraits(alignment = SampleAlignment.RIGHT, bayerPatternAtOutput = true))
            assertTrue(decoder.decodeInput(colour, sdk, CalibrationSelection(), CalibrationCapture()) is CompactLinearImage16)
            assertTrue(decoder.decodeInput(colour, key(colour, 1),
                CalibrationSelection(), CalibrationCapture()) is LinearImage)
            assertTrue(decoder.decodeInput(colour, key(colour).copy(target = StackTarget.PLANETARY),
                CalibrationSelection(), CalibrationCapture()) is LinearImage)
            val mono = frame(PixelFormat.MONO16)
            assertTrue(decoder.decodeInput(mono, key(mono),
                CalibrationSelection(), CalibrationCapture()) is LinearImage)
        } finally { decoder.release() }
    }

    @Test fun sessionPassesCompactSamplesToTheEngineWithoutExpandingAFloatImage() = runTest {
        val input = frame(PixelFormat.RGB48)
        val settings = key(input)
        val decoder = LiveFrameDecoder()
        var received: LinearSamples? = null
        val engine = object : LiveStackEngine {
            override fun add(image: LinearImage, exposureSeconds: Double, cancelled: () -> Boolean): LiveFrameOutcome =
                error("Compact input expanded into a full Float32 image")
            override fun add(image: LinearSamples, exposureSeconds: Double, cancelled: () -> Boolean): LiveFrameOutcome {
                received = image
                assertTrue(image is CompactLinearImage16)
                assertEquals(LiveFrameDecoder.outputStorageBytes(settings), image.storageBytes)
                return LiveFrameOutcome(true, 6, 0.0, 0.0)
            }
            override fun snapshot(state: LiveStackState): LiveStackSnapshot? = null
        }
        val session = LiveStackSession(this, { engine }, { frame, key ->
            decoder.decodeInput(frame, key, CalibrationSelection(), CalibrationCapture())
        }, {}, onWorkerReset = decoder::release)
        try {
            session.start(settings)
            session.offer(input, settings)
            runCurrent()
            assertEquals(LiveStackPhase.RUNNING, session.state.value.phase)
            assertEquals(1L, session.state.value.accepted)
            assertNotNull(received)
        } finally { session.close(); runCurrent() }
        assertEquals(0L, decoder.retainedBytes)
    }

    @Test fun compactRgbInputsStillAccumulateSubCodeMeansInFloat32AndExportTheirPrecisionMetadata() {
        val first = stars()
        val nextBytes = first.data.copyOf()
        for (i in nextBytes.indices step 2) {
            val code = (nextBytes[i].toInt() and 255) or ((nextBytes[i + 1].toInt() and 255) shl 8)
            nextBytes[i] = (code + 1).toByte()
            nextBytes[i + 1] = ((code + 1) ushr 8).toByte()
        }
        val second = first.copy(data = nextBytes, frameId = 2)
        val settings = key(first)
        val decoder = LiveFrameDecoder()
        val engine = LiveDeepSkyEngine(64L shl 20, previewMaxSide = 64)
        try {
            val a = decoder.decodeInput(first, settings, CalibrationSelection(), CalibrationCapture())
            val pixel = 8 * a.width + 8
            val low = a.sample(pixel * 3)
            assertTrue(engine.add(a, 5.0) { false }.accepted)
            val b = decoder.decodeInput(second, settings, CalibrationSelection(), CalibrationCapture())
            val high = b.sample(pixel * 3)
            assertTrue(engine.add(b, 5.0) { false }.accepted)
            val expected = (low.toDouble() + high.toDouble()) / 2
            assertTrue(high > low)
            val snapshot = engine.exportSnapshot(LiveStackState(accepted = 2))!!
            assertEquals(expected, snapshot.image.pixels[pixel * 3].toDouble(), 1e-7)
            assertTrue(snapshot.image.pixels[pixel * 3] > low)
            assertTrue(snapshot.image.pixels[pixel * 3] < high)
            assertEquals(2f, snapshot.weights[pixel], 0f)
            assertEquals(CompactLinearImage16.ENCODING, snapshot.details["inputEncoding"])
            assertTrue((snapshot.details.getValue("maximumInputQuantizationError") as Double) > 0)
            repeat(30) { frame ->
                val source = if (frame % 2 == 0) first else second
                val image = decoder.decodeInput(source, settings, CalibrationSelection(), CalibrationCapture())
                assertTrue(engine.add(image, 5.0) { false }.accepted)
            }
            val averaged = engine.exportSnapshot(LiveStackState(accepted = 32))!!
            assertEquals(expected, averaged.image.pixels[pixel * 3].toDouble(), 3e-7)
            assertEquals(32f, averaged.weights[pixel], 0f)
        } finally { decoder.release() }
    }

    private fun stars(): FrameData {
        val width = 384
        val height = 320
        val data = FloatArray(width * height * 3) { 2000f }
        val random = java.util.Random(1234)
        val points = ArrayList<Pair<Double, Double>>()
        while (points.size < 28) {
            val x = 28 + random.nextDouble() * (width - 56)
            val y = 28 + random.nextDouble() * (height - 56)
            if (points.any { hypot(it.first - x, it.second - y) < 25 }) continue
            points += x to y
            val amplitude = 12000 + random.nextDouble() * 18000
            for (sy in y.toInt() - 10..y.toInt() + 10) for (sx in x.toInt() - 10..x.toInt() + 10) {
                val signal = amplitude * exp(-((sx - x) * (sx - x) + (sy - y) * (sy - y)) / (2 * 2.4 * 2.4))
                for (channel in 0..2) data[(sy * width + sx) * 3 + channel] += (signal * (1 - .2 * channel)).toFloat()
            }
        }
        val bytes = ByteBuffer.allocate(data.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (sample in data) bytes.putShort(sample.roundToInt().toShort())
        return FrameData(bytes.array(), width, height, PixelFormat.RGB48, 1, 0)
    }
}
