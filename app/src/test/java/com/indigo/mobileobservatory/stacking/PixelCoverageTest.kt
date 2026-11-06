package com.indigo.mobileobservatory.stacking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelCoverageTest {
    private class TooManyFrames : PlanetaryFrameSource {
        var reads = 0
        override val frameCount = DeepSkyStacker.MAX_STACK_FRAMES.toLong() + 1
        override val width = 16
        override val height = 16
        override val channels = 1
        override fun readFrame(index: Long): LinearImage {
            reads++
            return LinearImage(width, height, channels, FloatArray(width * height) { .04f })
        }
        override fun close() = Unit
    }

    @Test fun packedRunsPreserveEveryUInt16CountAndCompressUniformCoverage() {
        val values = ShortArray(100_000) { if (it < 60_000) 500.toShort() else 65_535.toShort() }
        values[73_421] = 12
        val coverage = PixelCoverage.pack(values)

        assertEquals(values.size, coverage.size)
        assertEquals(4, coverage.starts!!.size)
        assertEquals(12, coverage.countAt(73_421))
        assertEquals(12, coverage.minimum)
        assertEquals(65_535, coverage.maximum)
        assertTrue(coverage.storageBytes <= values.size * 2L)
        for (index in values.indices) assertEquals(values[index].toInt() and 0xffff, coverage.countAt(index))
    }

    @Test fun frameLimitIsEnforcedBeforeReadingAndCoverageRejectsOverflow() {
        val source = TooManyFrames()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            OfflineDeepSkyStacker().stack(source)
        }
        assertEquals("deep_sky_frame_count", failure.message)
        assertEquals(0, source.reads)

        val buffer = CoverageBuffer(shortArrayOf(65_534.toShort()))
        buffer.increment(0)
        assertEquals(65_535, buffer[0])
        assertThrows(IllegalStateException::class.java) { buffer.increment(0) }
    }

    @Test fun emptyCoverageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PixelCoverage.pack(ShortArray(0)) }
    }
}
