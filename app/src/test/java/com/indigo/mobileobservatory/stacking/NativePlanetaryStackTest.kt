package com.indigo.mobileobservatory.stacking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class NativePlanetaryStackTest {
    private val alignment = PlanetaryAlignment(
        PlanetaryShift(0.0, 0.0, 1.0),
        emptyList(),
        0,
        PlanetaryRegistrationMode.GLOBAL
    )

    @Test
    fun nativeAccumulatorAveragesFramesAndPreservesCoverage() {
        val accumulator = requireNotNull(NativePlanetaryAccumulator.create(4, 3, 1))
        accumulator.use {
            val warp = PlanetaryWarp(4, 3, alignment)
            val frame = LinearImage(4, 3, 1, FloatArray(12) { it.toFloat() })
            repeat(2) {
                accumulator.accumulate(frame, warp, StackCancelCheck { false })
            }
            val output = FloatArray(12)
            val coverage = accumulator.finish(output, StackCancelCheck { false })
            assertArrayEquals(frame.pixels, output, 0f)
            assertEquals(List(12) { 2 }, coverage.toFloatArray().map { it.toInt() })
        }
    }

    @Test
    fun nativeAccumulatorMapsCancellationWithoutMutation() {
        val accumulator = requireNotNull(NativePlanetaryAccumulator.create(4, 3, 1))
        accumulator.use {
            val warp = PlanetaryWarp(4, 3, alignment)
            val frame = LinearImage(4, 3, 1, FloatArray(12) { 1f })
            assertThrows(CancellationException::class.java) {
                accumulator.accumulate(frame, warp, StackCancelCheck { true })
            }
            val output = FloatArray(12) { 9f }
            val coverage = accumulator.finish(output, StackCancelCheck { false })
            assertArrayEquals(FloatArray(12), output, 0f)
            assertEquals(List(12) { 0 }, coverage.toFloatArray().map { it.toInt() })
        }
    }

    @Test
    fun nativeRgbAccumulationMatchesKotlinOracleWithNonFiniteSamples() {
        val width = 32
        val height = 24
        val point = { x: Double, y: Double ->
            PlanetaryAlignmentPoint(x, y, 6)
        }
        val localAlignment = PlanetaryAlignment(
            PlanetaryShift(1.375, -.75, .96),
            listOf(
                PlanetaryLocalShift(point(8.0, 8.0), PlanetaryShift(1.8, -.4, .91)),
                PlanetaryLocalShift(point(22.0, 10.0), PlanetaryShift(.7, -1.1, .93)),
                PlanetaryLocalShift(point(15.0, 18.0), PlanetaryShift(2.0, .5, .89))
            ),
            3,
            PlanetaryRegistrationMode.MULTIPOINT
        )
        val warp = PlanetaryWarp(width, height, localAlignment)
        val frame = LinearImage(width, height, 3, FloatArray(width * height * 3) { index ->
            val channel = index % 3
            val pixel = index / 3
            (pixel % width) * .005f + (pixel / width) * .003f + channel * .007f
        })
        frame.pixels[(12 * width + 16) * 3 + 2] = Float.NaN

        val expectedSums = DoubleArray(frame.pixels.size)
        val expectedWeights = CoverageBuffer(ShortArray(width * height))
        PlanetaryStacker().accumulate(frame, expectedSums, expectedWeights, warp) {}
        val expectedOutput = FloatArray(frame.pixels.size) { index ->
            val pixel = index / 3
            if (expectedWeights[pixel] > 0) (expectedSums[index] / expectedWeights[pixel]).toFloat() else 0f
        }

        NativePlanetaryAccumulator.create(width, height, 3)!!.use { accumulator ->
            accumulator.accumulate(frame, warp, StackCancelCheck { false })
            val output = FloatArray(frame.pixels.size)
            val coverage = accumulator.finish(output, StackCancelCheck { false })
            assertArrayEquals(expectedOutput, output, 0f)
            assertArrayEquals(
                IntArray(width * height) { expectedWeights[it] },
                coverage.toFloatArray().map { it.toInt() }.toIntArray()
            )
        }
    }
}
