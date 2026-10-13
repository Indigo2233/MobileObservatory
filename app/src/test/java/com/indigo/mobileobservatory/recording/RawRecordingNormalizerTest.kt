package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.stacking.ChannelOrder
import com.indigo.mobileobservatory.stacking.LinearPixelDecoder
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RawRecordingNormalizerTest {
    private fun encoded(values: IntArray, format: PixelFormat, width: Int = values.size, height: Int = 1,
                        order: ByteOrder = ByteOrder.LITTLE_ENDIAN): FrameData {
        val data = ByteBuffer.allocate(values.size * if (format.isHighBit) 2 else 1).order(order)
        for (v in values) if (format.isHighBit) data.putShort(v.toShort()) else data.put(v.toByte())
        return FrameData(data.array(), width, height, format, 47L, 123456L)
    }

    @Test fun allLeftAlignedTwelveBitCodesRoundTripThroughSerAndPserWithoutAliasing() {
        val source = encoded(IntArray(4096) { it shl 4 }, PixelFormat.MONO12)
        val original = source.data.copyOf()
        val canonical = RawRecordingNormalizer.normalize(source, PixelTraits(alignment = SampleAlignment.LEFT))
        assertEquals(source.frameId, canonical.frameId)
        assertEquals(source.timestamp, canonical.timestamp)
        assertNotSame(source.data, canonical.data)
        assertArrayEquals(original, source.data)
        val words = ByteBuffer.wrap(canonical.data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0..4095) assertEquals(i, words.short.toInt() and 65535)
        for (packed in listOf(false, true)) {
            val file = Files.createTempFile("normalized-recording", if (packed) ".pser" else ".ser").toFile()
            try {
                if (packed) {
                    val writer = PSERWriter(file); writer.open(4096, 1, canonical.pixelFormat)
                    writer.writeFrame(canonical); writer.close()
                } else {
                    val writer = SERWriter(file); writer.open(4096, 1, canonical.pixelFormat)
                    writer.writeFrame(canonical); writer.close()
                }
                SERReader(file).use { reader ->
                    reader.sampleAlignment = SampleAlignment.RIGHT
                    assertTrue(reader.open())
                    val decoded = reader.readLinearFrame(0)!!
                    for (i in 0..4095) assertEquals(i / 4095f, decoded.pixels[i], 0f)
                }
            } finally { file.delete() }
        }
    }

    @Test fun dimAndBrightFramesUseTheSameExplicitAlignment() {
        val traits = PixelTraits(alignment = SampleAlignment.LEFT, validBits = 12)
        for (value in intArrayOf(1, 2, 63, 64, 1024, 4095)) {
            val source = encoded(intArrayOf(value shl 4), PixelFormat.MONO16)
            val normalized = RawRecordingNormalizer.normalize(source, traits)
            assertEquals(PixelFormat.MONO12, normalized.pixelFormat)
            assertEquals(value / 4095f, LinearPixelDecoder.decode(normalized, PixelTraits(alignment = SampleAlignment.RIGHT)).pixels[0], 0f)
        }
    }

    @Test fun everyCfaAndOddRoiKeepsActualColorsWithoutChangingGeometryOrData() {
        val rgb = intArrayOf(4001, 2002, 503)
        for (pattern in listOf("RG", "GR", "GB", "BG")) for (ox in 0..1) for (oy in 0..1) {
            val rx = if (pattern == "GR" || pattern == "BG") 1 else 0
            val ry = if (pattern == "GB" || pattern == "BG") 1 else 0
            val values = IntArray(36) { i ->
                val x = (i % 6 + ox) and 1; val y = (i / 6 + oy) and 1
                rgb[if (x == rx && y == ry) 0 else if (x != rx && y != ry) 2 else 1] shl 4
            }
            val source = encoded(values, PixelFormat.valueOf("BAYER_${pattern}12"), 6, 6)
            val traits = PixelTraits(alignment = SampleAlignment.LEFT, roiX = ox, roiY = oy, binning = 3, bayerPatternAtOutput = false)
            val normalized = RawRecordingNormalizer.normalize(source, traits)
            assertEquals(6, normalized.width); assertEquals(6, normalized.height)
            assertEquals(RawRecordingNormalizer.canonicalFormat(source.pixelFormat, traits), normalized.pixelFormat)
            val decoded = LinearPixelDecoder.decode(normalized, PixelTraits(alignment = SampleAlignment.RIGHT))
            for (i in 0 until 36) for (c in 0..2) assertEquals(rgb[c] / 4095f, decoded.pixels[i * 3 + c], 1e-6f)
        }
    }

    @Test fun bigEndianBgrBecomesLittleEndianRgbAndPreservesInput() {
        val original = encoded(intArrayOf(12345, 23456, 34567, 45678, 56789, 65535), PixelFormat.RGB48, 2, 1, ByteOrder.BIG_ENDIAN)
        val bytes = original.data.copyOf()
        val result = RawRecordingNormalizer.normalize(original, PixelTraits(byteOrder = ByteOrder.BIG_ENDIAN,
            alignment = SampleAlignment.RIGHT, channelOrder = ChannelOrder.BGR))
        val words = ByteBuffer.wrap(result.data).order(ByteOrder.LITTLE_ENDIAN)
        for (expected in intArrayOf(34567, 23456, 12345, 65535, 56789, 45678)) assertEquals(expected, words.short.toInt() and 65535)
        assertArrayEquals(bytes, original.data)
        val low = encoded(intArrayOf(1, 2, 3), PixelFormat.RGB24, 1, 1)
        val lowResult = RawRecordingNormalizer.normalize(low, PixelTraits(alignment = SampleAlignment.RIGHT, channelOrder = ChannelOrder.BGR))
        assertArrayEquals(byteArrayOf(3, 2, 1), lowResult.data)
    }

    @Test fun subSixteenBitRgbPreservesBrightnessAndEveryDistinctSensorCode() {
        val values = IntArray(4096 * 3) { it / 3 }
        val frame = encoded(values, PixelFormat.RGB48, 4096, 1)
        val result = RawRecordingNormalizer.normalize(frame, PixelTraits(alignment = SampleAlignment.RIGHT, validBits = 12))
        val decoded = LinearPixelDecoder.decode(result)
        for (i in 0..4095) assertEquals(i / 4095f, decoded.pixels[i * 3], .5f / 65535 + 1e-7f)
        for (i in 1..4095) assertTrue(decoded.pixels[i * 3] > decoded.pixels[(i - 1) * 3])
    }

    @Test fun ambiguousAlignmentAndEvenSensorCfaBinRejectBeforeRecording() {
        val frame = encoded(IntArray(4), PixelFormat.BAYER_RG12, 2, 2)
        assertThrows(IllegalArgumentException::class.java) { RawRecordingNormalizer.normalize(frame, PixelTraits()) }
        assertThrows(IllegalArgumentException::class.java) {
            RawRecordingNormalizer.normalize(frame, PixelTraits(alignment = SampleAlignment.RIGHT, binning = 2, bayerPatternAtOutput = false))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawRecordingNormalizer.normalize(frame.copy(data = ByteArray(1)), PixelTraits(alignment = SampleAlignment.RIGHT))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawRecordingNormalizer.normalize(encoded(intArrayOf(4096), PixelFormat.MONO12), PixelTraits(alignment = SampleAlignment.RIGHT))
        }
    }
}
