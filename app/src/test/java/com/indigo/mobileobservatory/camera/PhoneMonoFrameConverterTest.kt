package com.indigo.mobileobservatory.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class PhoneMonoFrameConverterTest {
    private fun raw(width: Int, height: Int, stride: Int, pixelStride: Int = 2, prefix: Int = 0,
                    sample: (Int, Int) -> Int): ByteBuffer {
        val count = (height - 1) * stride + (width - 1) * pixelStride + 2
        val bytes = ByteArray(prefix + count) { 91 }
        for (y in 0 until height) for (x in 0 until width) {
            val offset = prefix + y * stride + x * pixelStride; val value = sample(x, y)
            bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte()
        }
        return ByteBuffer.wrap(bytes).apply { position(prefix) }
    }
    private fun value(frame: FrameData, x: Int, y: Int): Int {
        val index = (y * frame.width + x) * 2
        return (frame.data[index].toInt() and 255) or ((frame.data[index+1].toInt() and 255) shl 8)
    }
    @Test fun ordinaryTwoByTwoOutputMatchesTheOriginalAverageWithOddStridesAndTruncatedRowPadding() {
        val width = 9; val height = 7; val stride = 31; val pixelStride = 3
        val input = raw(width, height, stride, pixelStride, 5) { x, y -> 1000 + y * 900 + x * 17 }
        val position = input.position(); val original = input.array().copyOf()
        val plan = PhoneMonoFrameConverter.plan(width, height, 2048, true)
        val frame = PhoneMonoFrameConverter.convert(input, stride, pixelStride, plan, true, 13, 17)
        assertEquals(2, plan.blockSize); assertEquals(4, frame.width); assertEquals(3, frame.height)
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val expected = (0..1).sumOf { dy -> (0..1).sumOf { dx -> 1000 + (y * 2 + dy) * 900 + (x * 2 + dx) * 17 } } / 4
            assertEquals(expected, value(frame, x, y))
        }
        assertEquals(position, input.position()); assertArrayEquals(original, input.array())
        assertEquals(PixelFormat.MONO16, frame.pixelFormat); assertEquals(13L, frame.frameId); assertEquals(17L, frame.timestamp)
    }
    @Test fun eightAndSixteenPixelBlocksAverageEveryBayerSampleWithoutOverflowOrAliasing() {
        val width = 64; val height = 48; val stride = width * 2 + 1
        for ((limit, block) in listOf(8 to 8, 4 to 16)) {
            val input = raw(width, height, stride) { x, y -> 60000 + y * 23 + x * 7 }
            val plan = PhoneMonoFrameConverter.plan(width, height, limit, true)
            val frame = PhoneMonoFrameConverter.convert(input, stride, 2, plan, true, 1, 2)
            assertEquals(block, plan.blockSize)
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                var sum = 0L
                for (dy in 0 until block) for (dx in 0 until block) sum += 60000 + (y * block + dy) * 23 + (x * block + dx) * 7
                assertEquals((sum / (block * block)).toInt(), value(frame, x, y))
            }
        }
    }
    @Test fun completeEvenBlocksPreserveEqualBayerPhaseWeightAndMaximumUnsignedValue() {
        for (limit in listOf(32, 8, 4)) {
            val plan = PhoneMonoFrameConverter.plan(64, 48, limit, true)
            val input = raw(64, 48, 129) { x, y -> listOf(1000, 2000, 3000, 4000)[(y % 2) * 2 + x % 2] }
            val frame = PhoneMonoFrameConverter.convert(input, 129, 2, plan, true, 1, 2)
            assertEquals(0, plan.blockSize % 2)
            assertTrue((0 until frame.width * frame.height).all { value(frame, it % frame.width, it / frame.width) == 2500 })
        }
        val saturated = raw(64, 48, 128) { _, _ -> 65535 }
        val full = PhoneMonoFrameConverter.convert(saturated, 128, 2, PhoneMonoFrameConverter.plan(64, 48, 4, true), true, 1, 2)
        assertEquals(65535, value(full, 0, 0))
    }
    @Test fun fiftyAndTwoHundredMegapixelRawPlansStayWithinTheRequestedMonoLongSide() {
        for ((width, height) in listOf(8192 to 6144, 16384 to 12288, 20000 to 10000)) {
            val plan = PhoneMonoFrameConverter.plan(width, height, 2048, true)
            assertTrue(maxOf(plan.outputWidth, plan.outputHeight) <= 2048)
            assertEquals(0, plan.blockSize % 2)
            assertTrue(plan.coveredWidth <= width && plan.coveredHeight <= height)
        }
        assertEquals(1, PhoneMonoFrameConverter.chooseStreamIndex(listOf(16384 to 12288, 8192 to 6144), 2048))
        assertEquals(1, PhoneMonoFrameConverter.chooseStreamIndex(listOf(640 to 480, 1920 to 1080, 8192 to 6144), 2048))
    }
    @Test fun sampledFootprintKeepsFractionalCoverageAndTheFirstAveragedPixelCenter() {
        val plan = PhoneMonoFrameConverter.plan(65, 49, 8, true)
        val footprint = plan.footprint(10.0, 12.0, 130.0, 98.0)
        assertEquals(10, plan.blockSize)
        assertEquals(120.0, footprint.widthPx, 0.0); assertEquals(80.0, footprint.heightPx, 0.0)
        assertEquals(19.0, footprint.firstPixelCenterX, 0.0); assertEquals(21.0, footprint.firstPixelCenterY, 0.0)
        val yuv = PhoneMonoFrameConverter.plan(2000, 1125, 2048, false).footprint(0.0, 0.0, 2000.0, 1500.0, false)
        assertEquals(187.5, yuv.topPx, 0.0); assertEquals(1125.0, yuv.heightPx, 0.0)
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedActivePixelDataIsRejectedInsteadOfFilledWithZero() {
        PhoneMonoFrameConverter.convert(ByteBuffer.allocate(8), 8, 2,
            PhoneMonoFrameConverter.plan(4, 4, 2048, true), true, 1, 2)
    }
    @Test fun cancellationIsCheckedBetweenRawRowsAndNeverReturnsAPartialSuccessfulFrame() {
        val input = raw(64, 48, 128) { x, y -> 1000 + x + y }
        val original = input.array().copyOf(); var rows = 0
        try {
            PhoneMonoFrameConverter.convert(input, 128, 2, PhoneMonoFrameConverter.plan(64, 48, 4, true), true, 1, 2) {
                rows++
                if (rows == 3) throw java.util.concurrent.CancellationException("Stopped")
            }
            fail("Cancelled conversion returned a successful partial frame")
        } catch (_: java.util.concurrent.CancellationException) { assertEquals(3, rows) }
        assertArrayEquals(original, input.array())
    }
}
