package com.indigo.mobileobservatory.stacking

import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class LivePlanetaryEngineTest {
    private val width = 160; private val height = 128
    private val budget = 64L * 1024 * 1024
    private val global = PlanetaryOptions(localAlignment = false, maxGlobalShift = 12)

    /** Independent continuous scene; translated test frames do not use the production resampler. */
    private fun planet(x: Double, y: Double): Float {
        val radius = hypot((x - 79.5) / 1.1, y - 63.5)
        val limb = .5 * (1 - tanh((radius - 44) / .8))
        val belts = .045 * sin(y * .47) + .025 * sin(y * .23 + x * .14)
        val spot = .11 * exp(-((x - 88).pow(2) / 20 + (y - 57).pow(2) / 11))
        val texture = .025 * sin(x * .61 + y * .24) * cos(y * .49)
        return (.012 + limb * (.37 + belts + spot + texture + .08 * max(0.0, 1 - radius / 44))).toFloat()
    }
    private fun frame(dx: Double = 0.0, dy: Double = 0.0, deform: Boolean = false, channels: Int = 1) =
        LinearImage(width, height, channels, FloatArray(width * height * channels) { i ->
            val p = i / channels; val x = (p % width).toDouble(); val y = (p / width).toDouble()
            val localX = if (deform) 2.2 * sin((y - 64) / 19) else 0.0
            val localY = if (deform) 1.7 * sin((x - 80) / 21) else 0.0
            planet(x - dx - localX, y - dy - localY) * if (channels == 1) 1f else when (i % channels) { 0 -> .65f; 1 -> .85f; else -> 1f }
        })
    private fun blur(image: LinearImage, passes: Int): LinearImage {
        var pixels = image.pixels.copyOf()
        repeat(passes) {
            val next = FloatArray(pixels.size)
            for (y in 0 until height) for (x in 0 until width) for (channel in 0 until image.channels) {
                var sum = 0.0
                for (dy in -1..1) for (dx in -1..1)
                    sum += pixels[((y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)) * image.channels + channel]
                next[(y * width + x) * image.channels + channel] = (sum / 9).toFloat()
            }
            pixels = next
        }
        return image.copy(pixels = pixels)
    }
    private fun mse(expected: LinearImage, actual: LinearImage): Double {
        var squared = 0.0; var count = 0
        for (y in 19 until 109) for (x in 26 until 134) {
            val p = (y * width + x) * expected.channels
            for (channel in 0 until expected.channels) {
                squared += (expected.pixels[p + channel] - actual.pixels[p + channel]).toDouble().pow(2); count++
            }
        }
        return squared / count
    }
    private fun state(version: Long, generation: Long = 2) = LiveStackState(
        phase = LiveStackPhase.RUNNING, accepted = version, imageVersion = version, generation = generation)

    @Test fun realTimeGlobalRegistrationImprovesTranslatedPlanetOverAnUnregisteredMean() {
        val truth = frame()
        val offsets = listOf(0.0 to 0.0, 2.371 to -1.763, -5.631 to 3.217, .537 to .617, 3.24 to 2.11)
        val engine = LivePlanetaryEngine(budget, global, qualityKeepPercent = 100.0, referenceWarmupFrames = 1)
        val unregistered = FloatArray(width * height)
        for ((dx, dy) in offsets) {
            val input = frame(dx, dy)
            val outcome = engine.add(input, .01) { false }
            assertTrue("$dx,$dy: $outcome", outcome.accepted)
            for (p in unregistered.indices) unregistered[p] += input.pixels[p] / offsets.size
        }
        val snapshot = engine.snapshot(state(offsets.size.toLong()))!!
        val correctedError = mse(truth, snapshot.image)
        val rawError = mse(truth, truth.copy(pixels = unregistered))
        assertTrue("Registered MSE=$correctedError; raw MSE=$rawError", correctedError < rawError * .15)
        assertEquals(offsets.size.toFloat(), snapshot.weights[64 * width + 80], 0f)
        assertTrue(snapshot.weights.any { it < offsets.size })
        assertEquals(.05, snapshot.details["effectiveExposureSeconds"] as Double, 1e-12)
        assertEquals(false, snapshot.details["rotationEstimated"])
    }

    @Test fun realTimeMultipointRegistrationImprovesDifferentialSeeingDetail() {
        val truth = frame(); val distorted = frame(1.25, -.75, deform = true)
        val globalEngine = LivePlanetaryEngine(budget, global.copy(maximumEdgeResidual = 1.0), 100.0, referenceWarmupFrames = 1)
        val localEngine = LivePlanetaryEngine(budget, PlanetaryOptions(
            alignmentPatchSize = 20, maxGlobalShift = 12, maxLocalShift = 5, maxAlignmentPoints = 128,
            maximumEdgeResidual = 1.0), 100.0, referenceWarmupFrames = 1)
        for (engine in listOf(globalEngine, localEngine)) {
            assertTrue(engine.add(truth, .01) { false }.accepted)
            val outcome = engine.add(distorted, .01) { false }
            assertTrue(outcome.toString(), outcome.accepted)
        }
        val local = localEngine.snapshot(state(2))!!
        val globalError = mse(truth, globalEngine.snapshot(state(2))!!.image)
        val localError = mse(truth, local.image)
        assertTrue("Local MSE=$localError; global MSE=$globalError", localError < globalError * .85)
        @Suppress("UNCHECKED_CAST") val modes = local.details["registrationModeCounts"] as Map<String, Long>
        assertEquals(2L, modes[PlanetaryRegistrationMode.MULTIPOINT.name])
        assertTrue((local.details["alignmentPoints"] as Int) >= 12)
    }

    @Test fun onlineQualityRejectsBlurWithoutChangingAlreadyAcceptedPixels() {
        val engine = LivePlanetaryEngine(budget, global, qualityKeepPercent = 50.0, qualityWindowFrames = 8, referenceWarmupFrames = 1)
        val sharp = frame()
        repeat(8) { assertTrue(engine.add(sharp, .01) { false }.accepted) }
        val before = engine.snapshot(state(8))!!
        val outcome = engine.add(blur(sharp, 6), .01) { false }
        assertFalse(outcome.accepted)
        assertEquals("QUALITY_THRESHOLD", outcome.reason)
        val after = engine.snapshot(state(8))!!
        assertArrayEquals(before.image.pixels, after.image.pixels, 0f)
        assertArrayEquals(before.weights, after.weights, 0f)
        assertEquals(8, after.details["qualityWindowSamples"])
        assertEquals(9L, after.details["qualityValidFrames"])
        assertEquals(8L, after.details["engineAcceptedFrames"])
        assertTrue((engine.snapshotDetails()["lastQualityScore"] as Double) < (engine.snapshotDetails()["qualityThreshold"] as Double))
    }

    @Test fun onlineSelectionKeepsEarlierContributionsRatherThanPretendingToRerankTheWholeStream() {
        val sharp = frame(); val earlyBlur = blur(sharp, 2)
        val engine = LivePlanetaryEngine(budget, global, 50.0)
        repeat(2) { assertEquals("REFERENCE_WARMUP", engine.add(earlyBlur, .01) { false }.reason) }
        assertTrue(engine.add(earlyBlur, .01) { false }.accepted)
        val first = engine.snapshot(state(1))!!
        assertTrue(engine.add(sharp, .02) { false }.accepted)
        val next = engine.snapshot(state(2))!!
        assertEquals(1L, next.details["referenceInputFrame"])
        assertEquals(2L, next.details["engineAcceptedFrames"])
        assertEquals(.03, next.details["effectiveExposureSeconds"] as Double, 1e-12)
        val error = mse(sharp, next.image)
        assertTrue(error > 1e-8)
        assertTrue(error < mse(sharp, first.image))
        assertEquals(first.details["referenceQuality"], next.details["referenceQuality"])
        assertTrue((next.details["onlineLimitation"] as String).contains("not global best-percent"))
    }

    @Test fun defaultStartupSelectsASharpOwnedReferenceAfterAHeavilyBlurredFirstFrame() {
        for (passes in listOf(12, 30)) {
            val truth = frame()
            val engine = LivePlanetaryEngine(budget)
            val initial = engine.add(blur(truth, passes), .01) { false }
            assertFalse(initial.accepted)
            assertEquals("REFERENCE_WARMUP", initial.reason)
            assertNull(engine.snapshot(state(0)))
            val borrowedCandidate = frame()
            assertEquals("REFERENCE_WARMUP", engine.add(borrowedCandidate, .02) { false }.reason)
            borrowedCandidate.pixels.fill(0f)
            val outcome = engine.add(truth, .03) { false }
            assertTrue("blur=$passes: $outcome", outcome.accepted)
            val result = engine.snapshot(state(1))!!
            assertArrayEquals(truth.pixels, result.image.pixels, 1e-6f)
            assertTrue(result.weights.all { it == 1f })
            assertEquals(2L, result.details["referenceInputFrame"])
            assertEquals(3, result.details["referenceWarmupFrames"])
            assertEquals(2, result.details["referenceWarmupDiscardedFrames"])
            assertEquals(1L, result.details["engineAcceptedFrames"])
            assertEquals("Only the actual third input contributes, not the retained second reference's exposure",
                .03, result.details["effectiveExposureSeconds"] as Double, 1e-12)
            assertTrue(engine.add(frame(), .04) { false }.accepted)
            assertEquals(.07, engine.snapshot(state(2))!!.details["effectiveExposureSeconds"] as Double, 1e-12)
        }
    }

    @Test fun laterSharperFramesExposeAnUnusableReferenceWithoutChangingOldCoordinatesOrCounters() {
        val truth = frame()
        val engine = LivePlanetaryEngine(budget)
        val poor = blur(truth, 12)
        repeat(2) { assertEquals("REFERENCE_WARMUP", engine.add(poor, .01) { false }.reason) }
        assertTrue(engine.add(poor, .01) { false }.accepted)
        val before = engine.snapshot(state(1))!!
        repeat(2) {
            val outcome = engine.add(truth, .01) { false }
            assertFalse(outcome.accepted)
            assertEquals("EDGE_MISMATCH", outcome.reason)
            assertFalse(outcome.requiresReset)
        }
        val outcome = engine.add(truth, .01) { false }
        assertFalse(outcome.accepted)
        assertTrue(outcome.requiresReset)
        assertEquals("REFERENCE_RESTART_REQUIRED", outcome.reason)
        val after = engine.snapshot(state(1))!!
        assertArrayEquals(before.image.pixels, after.image.pixels, 0f)
        assertArrayEquals(before.weights, after.weights, 0f)
        assertEquals(before.details["referenceInputFrame"], after.details["referenceInputFrame"])
        assertEquals(1L, after.details["engineAcceptedFrames"])
        assertEquals(.01, after.details["effectiveExposureSeconds"] as Double, 0.0)
        assertEquals(true, after.details["referenceRestartRequired"])
    }

    @Test fun invalidSignalAndUnregisterableOffsetsAreRejectedWithCoverageUnchanged() {
        val engine = LivePlanetaryEngine(budget, global.copy(maxGlobalShift = 8), 100.0, referenceWarmupFrames = 1)
        assertTrue(engine.add(frame(), .01) { false }.accepted)
        val before = engine.snapshot(state(1))!!
        val random = Random(4)
        val invalid = listOf(
            frame().copy(pixels = FloatArray(width * height)) to "LOW_SIGNAL",
            frame().copy(pixels = FloatArray(width * height) { 1f }) to "SATURATION",
            frame().copy(pixels = FloatArray(width * height) { (.15 + random.nextGaussian() * .035).toFloat() }) to "NOISE",
            frame(12.0) to "GLOBAL_MATCH"
        )
        for ((input, reason) in invalid) {
            val outcome = engine.add(input, .01) { false }
            assertFalse(outcome.toString(), outcome.accepted)
            assertEquals(reason, outcome.reason)
        }
        assertArrayEquals(before.weights, engine.snapshot(state(1))!!.weights, 0f)
        assertArrayEquals(before.image.pixels, engine.snapshot(state(1))!!.image.pixels, 0f)
    }

    @Test fun rgbRatiosExposureAndSnapshotVersionsSurviveInputReuseAndFurtherAccumulation() {
        val engine = LivePlanetaryEngine(budget, global, 100.0, referenceWarmupFrames = 1)
        val firstInput = frame(channels = 3)
        assertTrue(engine.add(firstInput, .01) { false }.accepted)
        val first = engine.snapshot(state(1, 7))!!
        val ownedPixels = first.image.pixels.copyOf(); val ownedWeights = first.weights.copyOf()
        firstInput.pixels.fill(0f)
        assertTrue(engine.add(frame(1.371, -.731, channels = 3), .02) { false }.accepted)
        val next = engine.snapshot(state(2, 7))!!
        assertArrayEquals(ownedPixels, first.image.pixels, 0f)
        assertArrayEquals(ownedWeights, first.weights, 0f)
        assertEquals(1L, first.state.imageVersion); assertEquals(2L, next.state.imageVersion)
        assertEquals(7L, next.state.generation)
        assertEquals(.03, next.details["effectiveExposureSeconds"] as Double, 1e-12)
        val p = (64 * width + 80) * 3
        assertEquals(.65, next.image.pixels[p].toDouble() / next.image.pixels[p + 2], 1e-6)
        assertEquals(.85, next.image.pixels[p + 1].toDouble() / next.image.pixels[p + 2], 1e-6)
    }

    @Test fun cancellationDuringAccumulationDiscardsPartialEngineButPreservesPublishedSnapshot() {
        val probe = LivePlanetaryEngine(budget, global, 100.0, referenceWarmupFrames = 1)
        probe.add(frame(), .01) { false }
        var totalChecks = 0
        probe.add(frame(.7, -.4), .01) { totalChecks++; false }
        val engine = LivePlanetaryEngine(budget, global, 100.0, referenceWarmupFrames = 1)
        engine.add(frame(), .01) { false }
        val before = engine.snapshot(state(1))!!
        val pixels = before.image.pixels.copyOf(); val weights = before.weights.copyOf()
        var checks = 0
        try {
            engine.add(frame(.7, -.4), .01) { ++checks > totalChecks - height / 2 }
            fail("Expected cancellation within accumulation")
        } catch (_: CancellationException) { }
        assertNull(engine.snapshot(state(1)))
        assertArrayEquals(pixels, before.image.pixels, 0f)
        assertArrayEquals(weights, before.weights, 0f)
        assertTrue(engine.add(frame(), .01) { false }.accepted)
        assertEquals(1L, engine.snapshot(state(1))!!.details["engineAcceptedFrames"])
    }

    @Test fun admissionRejectsBudgetGeometryExposureAndInvalidPixelsBeforeTheyCanPolluteAStack() {
        val tooSmall = LivePlanetaryEngine(1024L * 1024, global, 100.0, referenceWarmupFrames = 1)
        assertEquals("MEMORY_BUDGET", tooSmall.add(frame(), .01) { false }.reason)
        assertNull(tooSmall.snapshot(state(0)))
        val engine = LivePlanetaryEngine(budget, global, 100.0, referenceWarmupFrames = 1)
        assertTrue(engine.add(frame(), .01) { false }.accepted)
        assertEquals("INVALID_EXPOSURE", engine.add(frame(), Double.NaN) { false }.reason)
        val badPixels = frame().apply { pixels[5] = Float.NaN }
        assertEquals("INVALID_PIXELS", engine.add(badPixels, .01) { false }.reason)
        val changed = LinearImage(width, height + 1, 1, FloatArray(width * (height + 1)))
        assertEquals("GEOMETRY", engine.add(changed, .01) { false }.reason)
        assertEquals(1L, engine.snapshot(state(1))!!.details["engineAcceptedFrames"])
        assertTrue(LivePlanetaryEngine.estimateWorkingBytes(width, height, 3) > LivePlanetaryEngine.estimateWorkingBytes(width, height, 1))
        try {
            LivePlanetaryEngine.estimateWorkingBytes(Int.MAX_VALUE, Int.MAX_VALUE, 3)
            fail("Overflowed an unsafe geometry")
        } catch (_: ArithmeticException) { }
    }
}
