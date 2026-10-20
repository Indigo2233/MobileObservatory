package com.indigo.mobileobservatory.stacking

import java.nio.file.Files
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class PlanetaryBufferReuseTest {
    private fun images(channels: Int) = List(5) { frame ->
        val w = 160; val h = 128
        LinearImage(w, h, channels, FloatArray(w * h * channels) { i ->
            val x = (i / channels % w).toDouble() - (frame % 3) * .6
            val y = (i / channels / w).toDouble() + (frame % 2) * .4
            val limb = .5 * (1 - tanh((hypot(x - 80, y - 64) - 43) / .8))
            val value = .01 + limb * (.4 + .055 * sin(y * .47) + .025 * cos(x * .53 + y * .3))
            (value * (1 - i % channels * .12)).toFloat()
        })
    }

    private open class OwnedSource(val images: List<LinearImage>) : PlanetaryFrameSource {
        override val width = images.first().width
        override val height = images.first().height
        override val channels = images.first().channels
        override val frameCount = images.size.toLong()
        var ownedReads = 0
        override fun readFrame(index: Long) = images[index.toInt()].deepCopy().also { ownedReads++ }
        override fun exposureSeconds(index: Long) = .01 + index * .001
        override fun close() = Unit
    }

    private class BufferedSource(images: List<LinearImage>) : OwnedSource(images), BufferedPlanetaryFrameSource {
        val rgbBuffers = Collections.newSetFromMap(IdentityHashMap<FloatArray, Boolean>())
        val grayBuffers = Collections.newSetFromMap(IdentityHashMap<FloatArray, Boolean>())
        var grayReads = 0
        var lastInput: FloatArray? = null
        override fun readFrameInto(index: Long, destination: FloatArray): LinearImage {
            rgbBuffers += destination; lastInput = destination
            images[index.toInt()].pixels.copyInto(destination)
            return LinearImage(width, height, channels, destination)
        }
        override fun readLuminanceInto(index: Long, destination: FloatArray): LinearImage {
            grayBuffers += destination; grayReads++
            PlanetarySampling.fillPlane(images[index.toInt()], destination) {}
            return LinearImage(width, height, 1, destination)
        }
    }

    @Test fun bufferedPipelineMatchesOwnedSelectionPixelsWeightsAndStatistics() {
        val directory = Files.createTempDirectory("planetary-buffers-").toFile()
        try {
            for (channels in listOf(1, 3)) for (local in listOf(false, true)) {
                val originals = images(channels)
                val owned = OwnedSource(originals)
                val buffered = BufferedSource(originals)
                val stacker = PlanetaryStacker()
                val options = PlanetaryOptions(localAlignment = local)
                stacker.analyze(owned, options, directory).use { expectedAnalysis ->
                    stacker.analyze(buffered, options, directory).use { actualAnalysis ->
                        assertEquals(0, buffered.ownedReads)
                        assertEquals(originals.size, buffered.grayReads)
                        assertEquals(1, buffered.grayBuffers.size)
                        assertArrayEquals(expectedAnalysis.histogram, actualAnalysis.histogram)
                        assertEquals(expectedAnalysis.referenceFrame, actualAnalysis.referenceFrame)
                        var previous: PlanetaryStackResult? = null
                        var previousPixels: FloatArray? = null
                        for (selection in listOf(PlanetarySelection(100.0), PlanetarySelection(50.0), PlanetarySelection(count = 1))) {
                            buffered.rgbBuffers.clear()
                            val expected = stacker.stack(owned, expectedAnalysis, selection)
                            val actual = stacker.stack(buffered, actualAnalysis, selection)
                            assertArrayEquals(expected.image.pixels, actual.image.pixels, 0f)
                            assertArrayEquals(expected.weights, actual.weights, 0f)
                            assertEquals(expected.stats.copy(elapsedMillis = 0, estimatedPeakBytes = 0),
                                actual.stats.copy(elapsedMillis = 0, estimatedPeakBytes = 0))
                            assertEquals(0, buffered.ownedReads)
                            assertEquals(1, buffered.rgbBuffers.size)
                            assertSame("Final image transfers the caller's input buffer", buffered.lastInput, actual.image.pixels)
                            previous?.let { assertArrayEquals(previousPixels, it.image.pixels, 0f) }
                            previous = actual; previousPixels = actual.image.pixels.copyOf()
                        }
                        originals.zip(images(channels)).forEach { (before, after) -> assertArrayEquals(before.pixels, after.pixels, 0f) }
                    }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationDuringBorrowedAccumulationAllowsRetryAndPreservesAnalysis() {
        val directory = Files.createTempDirectory("planetary-buffers-cancel-").toFile()
        try {
            val source = BufferedSource(images(3))
            val stacker = PlanetaryStacker()
            stacker.analyze(source, PlanetaryOptions(localAlignment = false), directory).use { analysis ->
                var stacking = false; var checks = 0
                try {
                    stacker.stack(source, analysis, PlanetarySelection(100.0), PlanetaryControl(
                        checkCancelled = { if (stacking && ++checks > 5) throw CancellationException("test") },
                        progress = { if (it.phase == PlanetaryPhase.STACK) stacking = true }))
                    fail("Cancellation expected")
                } catch (_: CancellationException) { }
                assertTrue(analysis.indexFile.isFile)
                val result = stacker.stack(source, analysis, PlanetarySelection(100.0))
                assertEquals(source.frameCount, result.stats.stackedFrames)
                assertEquals(0, source.ownedReads)
            }
        } finally { directory.deleteRecursively() }
    }
}
