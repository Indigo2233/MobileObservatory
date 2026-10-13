package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.PSERWriter
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SERWriter
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException

class CalibrationSensorInputTest {
    private fun frame(values: IntArray, format: PixelFormat, width: Int, height: Int,
                      order: ByteOrder = ByteOrder.LITTLE_ENDIAN, shift: Int = 0): FrameData {
        val b = ByteBuffer.allocate(values.size * if (format.isHighBit) 2 else 1).order(order)
        for (v in values) if (format.isHighBit) b.putShort((v shl shift).toShort()) else b.put((v shl shift).toByte())
        return FrameData(b.array(), width, height, format, 1, 0)
    }

    @Test fun sensorDecodeRetainsEveryNativeDepthAndBothAlignmentsBeforeDemosaic() {
        for (bits in listOf(8, 10, 12, 14, 16)) for (left in listOf(false, true)) for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val format = PixelFormat.valueOf("BAYER_RG$bits"); val max = (1 shl bits) - 1
            val values = intArrayOf(1, max / 3, max / 3 + 1, max)
            val input = frame(values, format, 2, 2, order, if (left) (if (bits == 8) 8 else 16) - bits else 0)
            val original = input.data.copyOf()
            val sensor = LinearPixelDecoder.decodeSensor(input, PixelTraits(order, if (left) SampleAlignment.LEFT else SampleAlignment.RIGHT, bits))
            assertEquals(1, sensor.channels)
            for (i in values.indices) assertEquals(values[i].toFloat() / max, sensor.pixels[i], 0f)
            assertArrayEquals(original, input.data)
        }
    }

    @Test fun cfaCalibrationBeforeInterpolationRecoversKnownNegativeRgbAtEveryRoiPhase() {
        val formats = listOf(PixelFormat.BAYER_RG16, PixelFormat.BAYER_GR16, PixelFormat.BAYER_GB16, PixelFormat.BAYER_BG16)
        val colors = floatArrayOf(-.02f, .04f, .08f)
        for ((base, format) in formats.withIndex()) for (ox in 0..1) for (oy in 0..1) {
            val traits = PixelTraits(alignment = SampleAlignment.RIGHT, roiX = ox, roiY = oy, bayerPatternAtOutput = false)
            val geometry = CalibrationGeometry.from(4, 4, format, traits)
            val redX = (base and 1) xor ox; val redY = (base shr 1) xor oy
            fun color(i: Int): Int = when {
                i % 4 % 2 == redX && i / 4 % 2 == redY -> 0
                i % 4 % 2 != redX && i / 4 % 2 != redY -> 2
                else -> 1
            }
            val response = FloatArray(16) { if (it % 4 < 2) .5f else 1.5f }
            val biasPixels = FloatArray(16) { .1f + it * .001f }
            val capture = CalibrationCapture()
            fun master(type: CalibrationType, pixels: FloatArray, bias: CalibrationMaster? = null) = CalibrationBuilder.build(type,
                geometry, capture, 1, { LinearImage(4, 4, 1, pixels) }, bias = bias, allowUnknownCapture = true)
            val bias = master(CalibrationType.BIAS, biasPixels)
            val flat = master(CalibrationType.FLAT, FloatArray(16) { biasPixels[it] + .4f * response[it] }, bias)
            val light = LinearImage(4, 4, 1, FloatArray(16) { biasPixels[it] + colors[color(it)] * response[it] })
            val calibrated = CalibrationSelection(bias = bias, flat = flat, allowUnknownCapture = true).apply(light, geometry, capture)
            val rgb = LinearPixelDecoder.demosaicSensor(calibrated, format, traits)
            for (i in rgb.pixels.indices) assertEquals("$format/$ox/$oy/$i", colors[i % 3], rgb.pixels[i], 8e-8f)
            // A multiply varying flat cannot safely be divided after interpolation.
            val rawRgb = LinearPixelDecoder.demosaicSensor(light, format, traits)
            assertTrue(rawRgb.pixels[0] > 0f); assertTrue(rgb.pixels[0] < 0f)
        }
    }

    @Test fun serAndPserRawMasterMeanKeepsNativeCfaAndAdjacent12BitSamples() {
        for (packed in listOf(false, true)) {
            val file = Files.createTempFile("sensor-master", if (packed) ".pser" else ".ser").toFile()
            try {
                val frames = (0..2).map { k -> frame(IntArray(16) { 1000 + it + k }, PixelFormat.BAYER_GR12, 4, 4) }
                if (packed) {
                    val writer = PSERWriter(file); writer.open(4, 4, PixelFormat.BAYER_GR12)
                    frames.forEach(writer::writeFrame); writer.close()
                } else {
                    val writer = SERWriter(file); writer.open(4, 4, PixelFormat.BAYER_GR12)
                    frames.forEach(writer::writeFrame); writer.close()
                }
                SERReader(file).use { reader ->
                    assertTrue(reader.lastError, reader.open())
                    val geometry = CalibrationGeometry.from(4, 4, reader.sensorFormat, reader.sensorTraits)
                    assertEquals("GRBG", geometry.layout); assertEquals(12, geometry.validBits)
                    assertEquals(1, reader.readSensorFrame(0)!!.channels)
                    assertEquals(3, reader.readLinearFrame(0)!!.channels)
                    val master = CalibrationBuilder.build(CalibrationType.BIAS, geometry, CalibrationCapture(), 3,
                        { reader.readSensorFrame(it.toInt())!! }, allowUnknownCapture = true)
                    for (i in 0 until 16) assertEquals((1001 + i) / 4095f, master.imageCopy().pixels[i], 3e-8f)
                }
            } finally { file.delete() }
        }
    }

    @Test fun rgbBgrBigEndianSensorInputHasCanonicalChannelOrder() {
        val values = intArrayOf(300, 200, 100, 600, 500, 400)
        val sensor = LinearPixelDecoder.decodeSensor(frame(values, PixelFormat.RGB48, 2, 1, ByteOrder.BIG_ENDIAN),
            PixelTraits(ByteOrder.BIG_ENDIAN, SampleAlignment.RIGHT, 16, ChannelOrder.BGR))
        assertArrayEquals(floatArrayOf(100f, 200f, 300f, 400f, 500f, 600f).map { it / 65535f }.toFloatArray(), sensor.pixels, 0f)
        assertSame(sensor, LinearPixelDecoder.demosaicSensor(sensor, PixelFormat.RGB48))
    }

    @Test fun borrowedSensorDecodeReusesGeometryAndOwnedDecodeRemainsIndependent() {
        val workspace = LinearPixelDecoder.SensorWorkspace()
        val traits = PixelTraits(alignment=SampleAlignment.RIGHT,validBits=12)
        val firstFrame = frame(IntArray(8) { 100+it*17 },PixelFormat.BAYER_RG12,4,2)
        val secondFrame = frame(IntArray(8) { 3000-it*23 },PixelFormat.BAYER_RG12,4,2)
        val expectedFirst = LinearPixelDecoder.decodeSensor(firstFrame,traits)
        val first = LinearPixelDecoder.decodeSensorBorrowed(firstFrame,traits,workspace=workspace)
        assertArrayEquals(expectedFirst.pixels,first.pixels,0f)
        val retained = expectedFirst.pixels.copyOf()
        val second = LinearPixelDecoder.decodeSensorBorrowed(secondFrame,traits,workspace=workspace)
        assertSame(first.pixels,second.pixels)
        assertEquals(1L,workspace.arrayAllocations)
        assertEquals(8*4L,workspace.retainedBytes)
        assertArrayEquals(retained,expectedFirst.pixels,0f)

        val changed = LinearPixelDecoder.decodeSensorBorrowed(
            frame(IntArray(8) { it },PixelFormat.BAYER_RG12,2,4),traits,workspace=workspace)
        assertNotSame(first.pixels,changed.pixels)
        assertEquals(2L,workspace.arrayAllocations)
        workspace.release()
        assertEquals(0L,workspace.retainedBytes)
    }

    @Test fun cancelledAndInvalidBorrowedSensorDecodeRemainReusable() {
        val workspace = LinearPixelDecoder.SensorWorkspace()
        val traits = PixelTraits(alignment=SampleAlignment.RIGHT,validBits=12)
        val valid = frame(IntArray(8) { 100+it },PixelFormat.BAYER_RG12,4,2)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            LinearPixelDecoder.decodeSensorBorrowed(valid,traits,workspace=workspace) {
                if (++checks == 2) throw CancellationException("decode")
            }
        }
        val invalid = frame(IntArray(8) { if (it == 4) 4096 else 100 },PixelFormat.BAYER_RG12,4,2)
        assertThrows(IllegalArgumentException::class.java) {
            LinearPixelDecoder.decodeSensorBorrowed(invalid,traits,workspace=workspace)
        }
        val expected = LinearPixelDecoder.decodeSensor(valid,traits)
        val actual = LinearPixelDecoder.decodeSensorBorrowed(valid,traits,workspace=workspace)
        assertArrayEquals(expected.pixels,actual.pixels,0f)
        workspace.release()
    }
}
