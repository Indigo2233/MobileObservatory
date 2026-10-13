package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DdfapdWorkspaceTest {
    @Test fun repeatedReuseMatchesIndependentGoldenValuesAcrossPhasesAndDimensions() {
        val cases = referenceCases()
        val workspace = DdfapdDemosaicer.Workspace()
        var dimensions: Pair<Int, Int>? = null
        var expectedAllocations = 0L
        repeat(3) {
            for (case in cases) {
                val shape = case.width to case.height
                if (shape != dimensions) expectedAllocations += 5
                dimensions = shape
                val result = process(case, workspace)
                assertGolden(case, result)
                val retained = result.pixels.copyOf()
                // Reuse the same scratch with another CFA and quite different signal first.
                val altered = case.sensor.copy(pixels = FloatArray(case.sensor.pixelCount) { p ->
                    case.sensor.pixels[p] * -.73f + 1.2f
                })
                LinearPixelDecoder.demosaicSensor(altered, PixelFormat.BAYER_BG16,
                    algorithm = DemosaicAlgorithm.DDFAPD, workspace = workspace)
                val again = process(case, workspace)
                assertGolden(case, again)
                assertArrayEquals(result.pixels, again.pixels, 0f)
                assertNotSame(result.pixels, again.pixels)
                assertArrayEquals("Later demosaic must not mutate a retained RGB image", retained, result.pixels, 0f)
                assertEquals(expectedAllocations, workspace.arrayAllocations)
                assertEquals(33L * case.width * case.height, workspace.scratchBytes)
            }
        }
        workspace.release()
        assertEquals(0L, workspace.scratchBytes)
    }

    @Test fun cancellationAtEveryStageLeavesWorkspaceReusableAndInputUntouched() {
        val case = referenceCases().first { it.width == 12 && it.height == 10 }
        val workspace = DdfapdDemosaicer.Workspace()
        val original = case.sensor.pixels.copyOf()
        assertThrows(CancellationException::class.java) {
            process(case, workspace) { throw CancellationException("before scratch allocation") }
        }
        assertEquals(0L, workspace.arrayAllocations)
        for (stopAfter in listOf(2, case.height + 2, 3 * case.height + 2,
            6 * case.height + 2, 9 * case.height + 2, 11 * case.height + 1)) {
            var checks = 0
            assertThrows(CancellationException::class.java) {
                DdfapdDemosaicer.demosaic(case.sensor.pixels, case.width, case.height, 1, 1, workspace) {
                    if (++checks == stopAfter) throw CancellationException("stage $stopAfter")
                }
            }
            assertEquals(stopAfter, checks)
            assertGolden(case, process(case, workspace))
            assertEquals(5L, workspace.arrayAllocations)
            assertArrayEquals(original, case.sensor.pixels, 0f)
        }
        workspace.release()
    }

    @Test fun invalidSampleDoesNotPoisonOrLockTheNextInvocation() {
        val case = referenceCases().first()
        val workspace = DdfapdDemosaicer.Workspace()
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val bad = case.sensor.pixels.copyOf().apply { this[size / 2] = invalid }
            assertThrows(IllegalArgumentException::class.java) {
                DdfapdDemosaicer.demosaic(bad, case.width, case.height, 0, 0, workspace)
            }
            assertGolden(case, process(case, workspace))
            assertEquals(5L, workspace.arrayAllocations)
        }
        workspace.release()
    }

    @Test fun changedGeometryAndExplicitReleaseDropCapacityWithoutRetainingOutput() {
        val workspace = DdfapdDemosaicer.Workspace()
        var allocations = 0L
        for ((width, height) in listOf(12 to 10, 10 to 12, 2 to 2, 7 to 5)) {
            val input = FloatArray(width * height) { (it % 11) * .23f - .9f }
            val expected = DdfapdDemosaicer.demosaic(input, width, height, 1, 0)
            val actual = DdfapdDemosaicer.demosaic(input, width, height, 1, 0, workspace)
            allocations += 5
            assertEquals(allocations, workspace.arrayAllocations)
            assertEquals(33L * width * height, workspace.scratchBytes)
            assertArrayEquals(expected.pixels, actual.pixels, 0f)
        }
        workspace.release()
        workspace.release()
        assertEquals(0L, workspace.scratchBytes)
        assertEquals(allocations, workspace.arrayAllocations)
        DdfapdDemosaicer.demosaic(FloatArray(35), 7, 5, 0, 0, workspace)
        assertEquals(allocations + 5, workspace.arrayAllocations)
        assertEquals(33L * 35, workspace.scratchBytes)
    }

    @Test fun decoderOverloadKeepsAlignmentRoiPhaseAndNonDdfapdAllocationBehavior() {
        val workspace = DdfapdDemosaicer.Workspace()
        val width = 7; val height = 5
        val bytes = ByteArray(width * height * 2)
        for (p in 0 until width * height) {
            val value = ((p * 109 + 7) % 4096) shl 4
            bytes[2 * p] = value.toByte(); bytes[2 * p + 1] = (value ushr 8).toByte()
        }
        val frame = FrameData(bytes, width, height, PixelFormat.BAYER_GB12, 42, 0)
        val traits = PixelTraits(alignment = SampleAlignment.LEFT, validBits = 12,
            roiX = 1, roiY = 1, bayerPatternAtOutput = false)
        for (algorithm in listOf(DemosaicAlgorithm.BILINEAR, DemosaicAlgorithm.VNG, DemosaicAlgorithm.DDFAPD)) {
            val expected = LinearPixelDecoder.decode(frame, traits, algorithm = algorithm)
            val actual = LinearPixelDecoder.decode(frame, traits, algorithm = algorithm, workspace = workspace)
            assertArrayEquals(expected.pixels, actual.pixels, 0f)
            assertEquals(if (algorithm == DemosaicAlgorithm.DDFAPD) 5L else 0L, workspace.arrayAllocations)
        }
        workspace.release()
        val mono = LinearImage(3, 3, 1, FloatArray(9) { it / 8f })
        assertSame(mono, LinearPixelDecoder.demosaicSensor(mono, PixelFormat.MONO16,
            algorithm = DemosaicAlgorithm.DDFAPD, workspace = workspace))
        assertEquals(0L, workspace.scratchBytes)
    }

    @Test fun concurrentUseOrReleaseFailsWhileOwnerCompletesNormally() {
        val workspace = DdfapdDemosaicer.Workspace()
        val case = referenceCases().first()
        val acquired = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<LinearImage?>()
        val worker = Thread {
            try {
                var checks = 0
                result.set(DdfapdDemosaicer.demosaic(case.sensor.pixels, case.width, case.height, 0, 0, workspace) {
                    if (++checks == 2) {
                        acquired.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                    }
                })
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { process(case, workspace) }
            assertThrows(IllegalStateException::class.java) { workspace.release() }
        } finally {
            resume.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertGolden(case, checkNotNull(result.get()))
        workspace.release()
        assertEquals(0L, workspace.scratchBytes)
    }

    private data class Reference(val width: Int, val height: Int, val format: PixelFormat,
                                 val sensor: LinearImage, val expected: DoubleArray)

    private fun referenceCases(): List<Reference> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/stacking/ddfapd-menon2007-reference.json"))
        val cases = stream.bufferedReader().use { JSONObject(it.readText()).getJSONArray("cases") }
        return List(cases.length()) { index ->
            val case = cases.getJSONObject(index)
            val width = case.getInt("width"); val height = case.getInt("height")
            val input = case.getJSONArray("input"); val expected = case.getJSONArray("expected")
            Reference(width, height, PixelFormat.valueOf("BAYER_${case.getString("pattern").take(2)}16"),
                LinearImage(width, height, 1, FloatArray(input.length()) { input.getDouble(it).toFloat() }),
                DoubleArray(expected.length()) { expected.getDouble(it) })
        }
    }

    private fun process(case: Reference, workspace: DdfapdDemosaicer.Workspace,
                        cancelCheck: () -> Unit = {}) = LinearPixelDecoder.demosaicSensor(case.sensor,
        case.format, algorithm = DemosaicAlgorithm.DDFAPD, workspace = workspace, cancelCheck = cancelCheck)

    private fun assertGolden(case: Reference, result: LinearImage) {
        assertEquals(case.expected.size, result.pixels.size)
        for (p in result.pixels.indices) assertEquals("${case.format} ${case.width} x ${case.height}, sample $p",
            case.expected[p], result.pixels[p].toDouble(), 2e-6)
    }
}
