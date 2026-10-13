package com.indigo.mobileobservatory.camera

import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PreviewRawLayoutTest {
    @Test fun visibleLightPreviewDoesNotApplyTheInfraredMatrixByDefault() {
        assertFalse(FrameProcessor().lp750Enabled)
    }

    @Test fun confirmedTwelveBitRightAndLeftLayoutsHaveIdenticalDisplayHistograms() {
        val values = intArrayOf(0, 1024, 2048, 4095)
        val processor = FrameProcessor()
        val right = processor.computeHistogram(frame(values), PixelTraits(validBits = 12, alignment = SampleAlignment.RIGHT)).bins.copyOf()
        val left = processor.computeHistogram(frame(values.map { it shl 4 }.toIntArray()),
            PixelTraits(validBits = 12, alignment = SampleAlignment.LEFT)).bins.copyOf()
        assertArrayEquals(right, left)
        assertEquals(1, right[0])
        assertEquals(1, right[256])
        assertEquals(1, right[512])
        assertEquals(1, right[1023])
    }

    @Test fun dimTwelveBitDataDoesNotGetDividedAsSixteenBitData() {
        val processor = FrameProcessor()
        val histogram = processor.computeHistogram(frame(intArrayOf(64, 128, 256, 512)),
            PixelTraits(validBits = 12, alignment = SampleAlignment.RIGHT))
        assertEquals(1, histogram.bins[16])
        assertEquals(1, histogram.bins[32])
        assertEquals(1, histogram.bins[64])
        assertEquals(1, histogram.bins[128])
        assertEquals(12, processor.getDetectedEffectiveBits())
    }

    @Test fun quantizedDarkSixteenBitFrameDoesNotOverrideItsConfirmedSdkDepth() {
        val processor = FrameProcessor()
        repeat(30) {
            processor.computeHistogram(frame(intArrayOf(0, 64, 128, 192)),
                PixelTraits(validBits = 16, alignment = SampleAlignment.RIGHT))
        }
        assertEquals(16, processor.getDetectedEffectiveBits())
    }

    @Test fun knownEndianIsAppliedBeforeDisplayScaling() {
        val processor = FrameProcessor()
        val values = intArrayOf(1024, 2048, 3072, 4095)
        val little = processor.computeHistogram(frame(values), PixelTraits(validBits = 12, alignment = SampleAlignment.RIGHT)).bins.copyOf()
        val big = processor.computeHistogram(frame(values, ByteOrder.BIG_ENDIAN),
            PixelTraits(validBits = 12, alignment = SampleAlignment.RIGHT, byteOrder = ByteOrder.BIG_ENDIAN)).bins.copyOf()
        assertArrayEquals(little, big)
    }

    private fun frame(values: IntArray, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): FrameData {
        val bytes = java.nio.ByteBuffer.allocate(values.size * 2).order(order)
        values.forEach { bytes.putShort(it.toShort()) }
        return FrameData(bytes.array(), 2, 2, PixelFormat.MONO16, 1, 0)
    }
}
