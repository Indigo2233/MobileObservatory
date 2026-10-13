package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.PSERWriter
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SERWriter
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SerCfaInterpretationTest {
    @Test fun allPatternsRecoverUnmarkedSerAndPackedSerWithoutChangingSensorSamples() {
        val directory = Files.createTempDirectory("cfa-override").toFile()
        try {
            for (mode in listOf(SerCfaInterpretation.RGGB, SerCfaInterpretation.BGGR, SerCfaInterpretation.GRBG, SerCfaInterpretation.GBRG)) {
                val width = 24; val height = 20
                val bytes = ByteArray(width * height * 2)
                for (y in 0 until height) for (x in 0 until width) {
                    val color = colorAt(mode, x, y)
                    val value = intArrayOf(3200, 1600, 400)[color]
                    val offset = (y * width + x) * 2
                    bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte()
                }
                val frame = FrameData(bytes, width, height, PixelFormat.MONO12, 0, 0)
                for (packed in listOf(false, true)) {
                    val file = directory.resolve("${mode.name}.$packed")
                    if (packed) PSERWriter(file).apply { open(width, height, frame.pixelFormat); writeFrame(frame); close() }
                    else SERWriter(file).apply { open(width, height, frame.pixelFormat); writeFrame(frame); close() }
                    val original = file.readBytes()
                    val plain = SERReader(file).use { reader ->
                        reader.sampleAlignment = SampleAlignment.RIGHT; assertTrue(reader.open())
                        assertEquals(1, reader.readLinearFrame(0)!!.channels)
                        reader.readSensorFrame(0)!!
                    }
                    SERReader(file, cfaInterpretation = mode).use { reader ->
                        reader.sampleAlignment = SampleAlignment.RIGHT; assertTrue(reader.open())
                        assertEquals(0, reader.header!!.colorId)
                        assertArrayEquals(plain.pixels, reader.readSensorFrame(0)!!.pixels, 0f)
                        assertEquals(mode.name, CalibrationGeometry.from(width, height, reader.sensorFormat, reader.sensorTraits).layout)
                        for (algorithm in DemosaicAlgorithm.values()) {
                            val decoded = reader.readLinearFrame(0, algorithm = algorithm)!!
                            assertEquals(3, decoded.channels)
                            val middle = ((height / 2) * width + width / 2) * 3
                            for (c in 0..2) assertEquals("$mode $packed $algorithm channel $c",
                                intArrayOf(3200, 1600, 400)[c] / 4095f, decoded.pixels[middle + c], 1e-4f)
                        }
                        val preview = reader.readPreviewFrame(0)!!
                        val middle = ((height / 2) * width + width / 2) * 3
                        for (c in 0..2) assertEquals(intArrayOf(3200, 1600, 400)[c] / 4095f, preview.pixels[middle + c], 1e-4f)
                    }
                    assertArrayEquals(original, file.readBytes())
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun monoDisablesDebayerAndUnknownSingleSampleHeaderRequiresExplicitOverride() {
        val file = Files.createTempFile("cfa-invalid", ".ser").toFile()
        try {
            SERWriter(file).apply {
                open(8, 8, PixelFormat.BAYER_RG8)
                writeFrame(FrameData(ByteArray(64) { (it + 20).toByte() }, 8, 8, PixelFormat.BAYER_RG8, 0, 0)); close()
            }
            SERReader(file, cfaInterpretation = SerCfaInterpretation.MONO).use { reader ->
                assertTrue(reader.open()); assertEquals(PixelFormat.MONO8, reader.sensorFormat)
                for (algorithm in DemosaicAlgorithm.values()) assertEquals(1, reader.readLinearFrame(0, algorithm = algorithm)!!.channels)
            }
            RandomAccessFile(file, "rw").use { it.seek(18); it.writeInt(Integer.reverseBytes(7)) }
            SERReader(file).use { assertFalse(it.open()); assertEquals("cfa_unknown_header", it.lastError) }
            SERReader(file, cfaInterpretation = SerCfaInterpretation.BGGR).use {
                assertTrue(it.open()); assertEquals(PixelFormat.BAYER_BG8, it.sensorFormat)
            }
        } finally { file.delete() }
    }

    @Test fun rgbAndBgrNeverBecomeRawAndDecodeIdentityIncludesEveryInputChoice() {
        for (mode in SerCfaInterpretation.values().filter { it != SerCfaInterpretation.AUTO }) {
            try { mode.sensorFormat(-1, 16); fail("Negative color declarations are invalid") }
            catch (error: IllegalArgumentException) { assertEquals("invalid_source", error.message) }
        }
        for (id in listOf(100, 101)) for (mode in SerCfaInterpretation.values().filter { it != SerCfaInterpretation.AUTO }) {
            try { mode.sensorFormat(id, 16); fail("RGB must retain three channels") }
            catch (error: IllegalArgumentException) { assertEquals("cfa_rgb_not_raw", error.message) }
        }
        val keys = SerCfaInterpretation.values().map { it.decodingIdentity("same-file", "COMPATIBILITY", "RIGHT", "DDFAPD") }
        assertEquals(keys.size, keys.toSet().size)
        assertNotEquals(keys.first(), SerCfaInterpretation.AUTO.decodingIdentity("same-file", "SPECIFICATION", "RIGHT", "DDFAPD"))
        assertNotEquals(keys.first(), SerCfaInterpretation.AUTO.decodingIdentity("same-file", "COMPATIBILITY", "LEFT", "DDFAPD"))
        assertNotEquals(keys.first(), SerCfaInterpretation.AUTO.decodingIdentity("same-file", "COMPATIBILITY", "RIGHT", "VNG"))
    }

    private fun colorAt(mode: SerCfaInterpretation, x: Int, y: Int): Int {
        val pattern = when (mode) {
            SerCfaInterpretation.RGGB -> intArrayOf(0, 1, 1, 2)
            SerCfaInterpretation.BGGR -> intArrayOf(2, 1, 1, 0)
            SerCfaInterpretation.GRBG -> intArrayOf(1, 0, 2, 1)
            SerCfaInterpretation.GBRG -> intArrayOf(1, 2, 0, 1)
            else -> error("Expected Bayer")
        }
        return pattern[(y and 1) * 2 + (x and 1)]
    }
}
