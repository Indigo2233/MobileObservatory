package com.indigo.mobileobservatory.stacking

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.*

class OfflineDeepSkyStackerTest {
    private val width = 192
    private val height = 160
    private val stars = buildList<Triple<Double, Double, Double>> {
        val random = Random(1234)
        while (size < 28) {
            val p = Triple(14 + random.nextDouble() * (width - 28), 14 + random.nextDouble() * (height - 28), .15 + random.nextDouble() * .4)
            if (none { hypot(it.first - p.first, it.second - p.second) < 12 }) add(p)
        }
    }

    private fun field(transform: RigidTransform = RigidTransform(), seed: Long = 1): LinearImage {
        val random = Random(seed)
        val pixels = FloatArray(width * height) { (.04 + random.nextGaussian() * .001).toFloat() }
        for ((x, y, amplitude) in stars) {
            val sx = transform.cosine * (x - transform.dx) + transform.sine * (y - transform.dy)
            val sy = -transform.sine * (x - transform.dx) + transform.cosine * (y - transform.dy)
            for (py in max(0, sy.toInt() - 10)..min(height - 1, sy.toInt() + 10))
                for (px in max(0, sx.toInt() - 10)..min(width - 1, sx.toInt() + 10))
                    pixels[py * width + px] += (amplitude * exp(-((px - sx).pow(2) + (py - sy).pow(2)) / (2 * 1.2 * 1.2))).toFloat()
        }
        return LinearImage(width, height, 1, pixels)
    }

    private class Source(val images: List<LinearImage>, val exposures: List<Double>) : PlanetaryFrameSource {
        var reads = 0
        var closed = false
        override val width = images.first().width
        override val height = images.first().height
        override val channels = images.first().channels
        override val frameCount = images.size.toLong()
        override fun readFrame(index: Long): LinearImage { reads++; return images[index.toInt()].deepCopy() }
        override fun exposureSeconds(index: Long) = exposures[index.toInt()]
        override fun close() { closed = true }
    }

    @Test fun offlineUsesIdenticalRigidRegistrationAndCoverageAsTheLiveScienceEngine() {
        val images = listOf(field(), field(RigidTransform(Math.toRadians(2.1), 4.3, -2.7), 2), field(RigidTransform(dx = -3.2, dy = 1.8), 3))
        val exposures = listOf(2.0, 3.0, 4.0)
        val live = DeepSkyStacker()
        images.forEachIndexed { index, image -> assertTrue(live.add(image, exposures[index]).accepted) }
        val expected = live.snapshot()!!
        val source = Source(images, exposures)
        val actual = OfflineDeepSkyStacker().stack(source)
        assertArrayEquals(expected.image.pixels, actual.image.pixels, 0f)
        assertArrayEquals(expected.weights, actual.weights, 0f)
        assertEquals(3, actual.stats.stack.accepted)
        assertEquals(9.0, actual.stats.stack.exposureSeconds, 0.0)
        assertEquals(0L, actual.stats.referenceFrame)
        assertEquals(0L, actual.stats.unknownExposureFrames)
        assertEquals(1f, actual.weights[0], 0f)
        assertEquals(3f, actual.weights[80 * width + 80], 0f)
        assertFalse("The caller retains ownership of its recording reader", source.closed)
    }

    @Test fun rejectedFramesDoNotContributeExposureAndUnknownExposureIsNeverInferred() {
        val blank = LinearImage(width, height, 1, FloatArray(width * height) { .04f })
        val source = Source(listOf(blank, field(), field(seed = 2), field(seed = 3)), listOf(99.0, 1.5, 0.0, 2.5))
        val result = OfflineDeepSkyStacker().stack(source)
        assertEquals(4, result.stats.stack.received)
        assertEquals(3, result.stats.stack.accepted)
        assertEquals(1, result.stats.stack.rejected)
        assertEquals(1L, result.stats.referenceFrame)
        assertEquals(1L, result.stats.unknownExposureFrames)
        assertEquals(4.0, result.stats.stack.exposureSeconds, 0.0)
        assertEquals(mapOf(DeepSkyRejection.TOO_FEW_STARS to 1L), result.stats.rejectionCounts)
    }

    @Test fun cancellationStopsBeforeReadingAnotherFrameAndPublishesNoPartialProduct() {
        val source = Source(List(3) { field(seed = it + 1L) }, List(3) { 1.0 })
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            OfflineDeepSkyStacker().stack(source, checkCancelled = { if (cancelled) throw CancellationException() },
                progress = { _, _, _ -> cancelled = true })
        }
        assertEquals(1, source.reads)
    }

    @Test fun memoryAdmissionHappensBeforeAnyDecodeAndAnEmptyStarFieldCannotProduceAResult() {
        val source = Source(listOf(field()), listOf(1.0))
        assertThrows(IllegalArgumentException::class.java) {
            OfflineDeepSkyStacker().stack(source, DeepSkyConfig(memoryBudgetBytes = 1))
        }
        assertEquals(0, source.reads)
        val empty = Source(listOf(LinearImage(width, height, 1, FloatArray(width * height))), listOf(0.0))
        val failure = assertThrows(IllegalStateException::class.java) { OfflineDeepSkyStacker().stack(empty) }
        assertEquals("deep_sky_no_accepted_frames", failure.message)
    }

    @Test fun rgbChannelsArePreservedAndWeightsCountFramesRatherThanColorSamples() {
        val mono = field()
        val rgb = LinearImage(width, height, 3, FloatArray(width * height * 3) { i -> mono.pixels[i / 3] * floatArrayOf(1f, .7f, .4f)[i % 3] })
        val result = OfflineDeepSkyStacker().stack(Source(listOf(rgb, rgb.deepCopy()), listOf(1.0, 1.0)))
        assertEquals(3, result.image.channels)
        assertArrayEquals(rgb.pixels, result.image.pixels, 1e-6f)
        assertTrue(result.weights.all { it == 2f })
    }
}
