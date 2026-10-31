package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.PixelFormat
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class OfflineDeepSkyBufferTest {
    private val width = 192
    private val height = 160

    private fun frames(channels: Int): List<LinearImage> {
        val random = Random(1234)
        val stars = buildList<Triple<Double, Double, Double>> {
            while (size < 28) {
                val next = Triple(14 + random.nextDouble() * (width - 28),
                    14 + random.nextDouble() * (height - 28), .15 + random.nextDouble() * .4)
                if (none { hypot(it.first - next.first, it.second - next.second) < 12 }) add(next)
            }
        }
        return List(9) { frame ->
            val pixels = FloatArray(width * height * channels) { (.04 + random.nextGaussian() * .001).toFloat() }
            if (frame > 0) for ((cx, cy, amplitude) in stars) {
                val sx = cx + frame * .17; val sy = cy - frame * .11
                for (y in max(0, sy.toInt() - 8)..min(height - 1, sy.toInt() + 8))
                    for (x in max(0, sx.toInt() - 8)..min(width - 1, sx.toInt() + 8))
                        for (c in 0 until channels) pixels[(y * width + x) * channels + c] +=
                            (amplitude * (1 - c * .2) * exp(-((x - sx).pow(2) + (y - sy).pow(2)) / 2.88)).toFloat()
            }
            if (frame == 8) for (c in 0 until channels) pixels[(80 * width + 100) * channels + c] = 4f
            LinearImage(width, height, channels, pixels)
        }
    }

    private open class Source(val frames: List<LinearImage>) : PlanetaryFrameSource {
        override val width = frames.first().width
        override val height = frames.first().height
        override val channels = frames.first().channels
        override val frameCount = frames.size.toLong()
        var ownedReads = 0
        override fun readFrame(index: Long) = frames[index.toInt()].deepCopy().also { ownedReads++ }
        override fun exposureSeconds(index: Long) = if (index == 2L) 0.0 else index + 1.0
        override fun close() = Unit
    }

    private open class BufferedSource(frames: List<LinearImage>) : Source(frames), BufferedPlanetaryFrameSource {
        val buffers = Collections.newSetFromMap(IdentityHashMap<FloatArray, Boolean>())
        var reads = 0
        override fun readFrameInto(index: Long, destination: FloatArray): LinearImage {
            reads++; buffers += destination
            frames[index.toInt()].pixels.copyInto(destination)
            return LinearImage(width, height, channels, destination)
        }
        override fun readLuminanceInto(index: Long, destination: FloatArray): LinearImage =
            error("Deep-sky accumulation must retain all colour channels")
    }

    private fun comparable(stats: DeepSkyStats) = stats.copy(elapsedMs = 0, estimatedWorkingBytes = 0)

    @Test fun bufferedAndOwnedFramesMatchTheCopyingEngineIncludingRejectionsAndWarmup() {
        for (channels in listOf(1, 3)) {
            val originals = frames(channels)
            val source = BufferedSource(originals)
            val copying = DeepSkyStacker()
            val expectedProgress = originals.mapIndexed { index, image ->
                comparable(copying.add(image, source.exposureSeconds(index.toLong())).stats)
            }
            val expected = copying.snapshot()!!
            val progress = mutableListOf<DeepSkyStats>()
            val actual = OfflineDeepSkyStacker().stack(source, progress = { _, _, s -> progress += comparable(s) })
            val owned = OfflineDeepSkyStacker().stack(Source(originals))
            assertArrayEquals(expected.image.pixels, actual.image.pixels, 0f)
            assertArrayEquals(expected.weights, actual.weights, 0f)
            assertArrayEquals(owned.image.pixels, actual.image.pixels, 0f)
            assertEquals(expectedProgress, progress)
            assertEquals(comparable(expected.stats), comparable(actual.stats.stack))
            assertTrue(actual.stats.stack.rejectedPixelSamples > 0)
            assertEquals(1L, actual.stats.referenceFrame)
            assertEquals(1L, actual.stats.unknownExposureFrames)
            assertEquals(mapOf(DeepSkyRejection.TOO_FEW_STARS to 1L), actual.stats.rejectionCounts)
            assertEquals(originals.size, source.reads)
            assertEquals(0, source.ownedReads)
            assertEquals(1, source.buffers.size)
            assertNotSame(source.buffers.single(), actual.image.pixels)
            val saved = actual.image.pixels.copyOf()
            val weights = actual.weights.copyOf()
            source.buffers.single().fill(Float.NaN)
            OfflineDeepSkyStacker().stack(source)
            assertArrayEquals(saved, actual.image.pixels, 0f)
            assertArrayEquals(weights, actual.weights, 0f)
            originals.zip(frames(channels)).forEach { (a, b) -> assertArrayEquals(a.pixels, b.pixels, 0f) }
        }
    }

    @Test fun resultTransferEndsThePassAndLeavesEarlierSnapshotsAndFuturePassesIndependent() {
        val image = frames(3)[1]
        val engine = DeepSkyStacker(livePreviewOverlap = false, reusableOfflineInput = true)
        assertTrue(engine.add(image, 2.0).accepted)
        val copied = engine.snapshot()!!
        val result = engine.takeResult()!!
        assertArrayEquals(copied.image.pixels, result.image.pixels, 0f)
        assertArrayEquals(copied.weights, result.weights, 0f)
        assertNull(engine.snapshot())
        assertNull(engine.takeResult())
        assertEquals(DeepSkyStats(), engine.stats)
        result.image.pixels[0] = -7f; result.weights[0] = 99f
        engine.reset()
        assertTrue(engine.add(image, 3.0).accepted)
        val next = engine.takeResult()!!
        assertNotEquals(-7f, copied.image.pixels[0])
        assertNotEquals(-7f, next.image.pixels[0])
        assertEquals(1f, copied.weights[0], 0f)
        assertEquals(1f, next.weights[0], 0f)
        assertEquals(-7f, result.image.pixels[0], 0f)
        assertEquals(99f, result.weights[0], 0f)
        assertThrows(IllegalStateException::class.java) { DeepSkyStacker().takeResult() }
    }

    @Test fun cancellationBeforeDecodeDuringAccumulationAndAtCompletionCanRetry() {
        val originals = frames(3)
        val expected = OfflineDeepSkyStacker().stack(Source(originals))
        for (whenToCancel in listOf("before", "accumulate", "complete")) {
            val source = BufferedSource(originals)
            var completed = 0L; var checks = 0
            assertThrows(CancellationException::class.java) {
                OfflineDeepSkyStacker().stack(source, checkCancelled = {
                    val cancel = when (whenToCancel) {
                        "before" -> true
                        "complete" -> completed == source.frameCount
                        else -> source.reads >= 2 && ++checks > 11
                    }
                    if (cancel) throw CancellationException(whenToCancel)
                }, progress = { done, _, _ -> completed = done })
            }
            if (whenToCancel == "before") assertEquals(0, source.reads)
            val result = OfflineDeepSkyStacker().stack(source)
            assertArrayEquals(expected.image.pixels, result.image.pixels, 0f)
            assertArrayEquals(expected.weights, result.weights, 0f)
        }
    }

    @Test fun brokenBorrowedBufferContractFailsWithoutPublishingAResult() {
        val originals = frames(3)
        val source = object : BufferedSource(originals) {
            override fun readFrameInto(index: Long, destination: FloatArray) = frames[index.toInt()].deepCopy()
        }
        val failure = assertThrows(IllegalArgumentException::class.java) { OfflineDeepSkyStacker().stack(source) }
        assertEquals("source_buffer_changed", failure.message)
    }

    @Test fun phaseBudgetKeepsCalibrationButReleasesDecoderBeforeRendering() {
        val decoder = 7L shl 20; val masters = 32L shl 20
        for (channels in listOf(1, 3)) {
            val base = OfflineDeepSkyMemory.estimate(2048, 2048, channels, true)
            val decoded = OfflineDeepSkyMemory.estimate(2048, 2048, channels, true, decoder)
            val calibrated = OfflineDeepSkyMemory.estimate(2048, 2048, channels, true, decoder, masters)
            assertEquals(base.stackingBytes + decoder, decoded.stackingBytes)
            assertEquals(base.previewBytes, decoded.previewBytes)
            assertEquals(base.exportBytes, decoded.exportBytes)
            assertEquals(decoded.stackingBytes + masters, calibrated.stackingBytes)
            assertEquals(decoded.previewBytes + masters, calibrated.previewBytes)
            assertEquals(decoded.exportBytes + masters, calibrated.exportBytes)
            assertEquals(maxOf(calibrated.stackingBytes, calibrated.previewBytes, calibrated.exportBytes), calibrated.peakBytes)
            if (channels == 1) assertTrue(base.peakBytes > base.stackingBytes)
            else assertEquals(base.stackingBytes, base.peakBytes)
        }
        val full = OfflineRegionPlan(3856, 2180, PixelFormat.BAYER_RG16, PixelRoi(0, 0, 3856, 2180),
            DemosaicAlgorithm.DDFAPD, StackTarget.DEEP_SKY)
        assertTrue(full.workingBytes < 331L shl 20)
        assertNull(full.issue(StackMemoryBudget.forHeap(512L shl 20)))
        assertNull(full.copy(softwareBin = 2).issue(StackMemoryBudget.forHeap(256L shl 20)))
        assertTrue(full.copy(softwareBin = 2).workingBytes < 93L shl 20)
        val crop = full.suggested(StackMemoryBudget.forHeap(256L shl 20))!!
        assertNull(full.copy(region = crop).issue(StackMemoryBudget.forHeap(256L shl 20)))
    }

    @Test fun admissionAndProgressUseTheSameFullPipelinePeakBeforeAnyRead() {
        val originals = frames(1)
        val source = BufferedSource(originals)
        val decoder = 3L shl 20; val masters = 16L shl 20
        val estimate = OfflineDeepSkyMemory.estimate(width, height, 1, true, decoder, masters)
        assertThrows(IllegalArgumentException::class.java) {
            OfflineDeepSkyStacker().stack(source, DeepSkyConfig(memoryBudgetBytes = estimate.peakBytes - 1),
                decoderWorkingBytes = decoder, retainedCalibrationBytes = masters)
        }
        assertEquals(0, source.reads)
        val result = OfflineDeepSkyStacker().stack(source, DeepSkyConfig(memoryBudgetBytes = estimate.peakBytes),
            progress = { _, _, stats -> assertEquals(estimate.peakBytes, stats.estimatedWorkingBytes) },
            decoderWorkingBytes = decoder, retainedCalibrationBytes = masters)
        assertEquals(estimate.peakBytes, result.stats.stack.estimatedWorkingBytes)
    }
}
