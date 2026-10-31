package com.indigo.mobileobservatory.stacking

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.Roi
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveBudgetPreflightInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test fun preflightAndStartRejectTheSamePlanWhenOtherHeapObjectsUseTheBudget() = runBlocking {
        val directory = File(app.cacheDir, "live-budget-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val c = StackingCoordinator(app, scope, calibrationDirectory = directory,
            heapUsageBytes = { Runtime.getRuntime().maxMemory() })
        try {
            await { c.calibration.state.value.initialized && !c.calibration.state.value.busy }
            val key = key()
            assertNull("This fixture fits the configured ceiling",
                LiveStackAdmission.issue(key, c.memoryBudgetBytes))
            val preflightIssue = c.liveInputIssue(key)
            main { c.startLive(key) }
            assertEquals("memory_budget", c.task.value.message)
            assertEquals(LiveStackPhase.EMPTY, c.live.state.value.phase)
            assertEquals("Inline readiness must include the same heap deductions as start",
                c.task.value.message, preflightIssue)
        } finally {
            main { c.close() }
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test fun reducedRoiAndBinUseTheBudgetThatActuallyAdmitsTheNextSession() = runBlocking {
        val directory = File(app.cacheDir, "live-budget-plan-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val heapUsed = AtomicLong()
        val c = StackingCoordinator(app, scope, calibrationDirectory = directory, heapUsageBytes = heapUsed::get)
        try {
            await { c.calibration.state.value.initialized && !c.calibration.state.value.busy }
            val key = key(512)
            val needed = LiveStackAdmission.workingBytes(key, 0, false)
            val reduced = LiveStackAdmission.workingBytes(key.copy(softwareBin = 2), 0, false)
            val available = (needed + reduced) / 2
            assertTrue(available < c.memoryBudgetBytes)
            heapUsed.set(c.heapLimitBytes - maxOf(16L shl 20, c.heapLimitBytes / 8) - available)
            assertEquals(available, c.liveMemoryBudgetBytes)
            assertEquals("memory_budget", c.liveInputIssue(key))
            assertEquals(2, LiveStackAdmission.recommendHardwareBin(key, 512, 512,
                listOf(1, 2, 4), c.liveMemoryBudgetBytes))
            val context = StackRoiContext(key, 512, 512, c.liveMemoryBudgetBytes)
            val roi = requireNotNull(StackRoiPlanner.maximum(context, StackRoiAspect.SENSOR))
            assertTrue(roi.width < 512)
            assertNull(context.issue(roi))
            assertNull(c.liveInputIssue(context.withRoi(roi)))
            main { c.startLive(context.withRoi(roi)) }
            assertEquals(LiveStackPhase.RUNNING, c.live.state.value.phase)
        } finally {
            main { c.close() }
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test fun resumingDoesNotCountTheRetainedAccumulatorAsAnotherNewAllocation() = runBlocking {
        val directory = File(app.cacheDir, "live-budget-resume-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val heapUsed = AtomicLong()
        val c = StackingCoordinator(app, scope, calibrationDirectory = directory, heapUsageBytes = heapUsed::get)
        try {
            await { c.calibration.state.value.initialized && !c.calibration.state.value.busy }
            val key = key(128)
            main { c.startLive(key); c.acceptFrame(starFrame(), key) }
            await { c.live.state.value.accepted > 0 || c.live.state.value.rejected > 0 ||
                c.live.state.value.phase == LiveStackPhase.FAILED }
            assertEquals(c.live.state.value.toString(), 1L, c.live.state.value.accepted)
            await { c.live.latestSnapshot != null }
            val generation = c.live.state.value.generation
            main { c.live.pause() }
            await { c.live.state.value.phase == LiveStackPhase.PAUSED }
            heapUsed.set(c.heapLimitBytes)
            assertNull(c.liveInputIssue(key))
            main { c.live.resume(key) }
            assertEquals(LiveStackPhase.RUNNING, c.live.state.value.phase)
            assertEquals(generation, c.live.state.value.generation)
            assertEquals(1L, c.live.state.value.accepted)
        } finally {
            main { c.close() }
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    private fun starFrame(): FrameData {
        val random = Random(725)
        val stars = List(20) { Triple(12 + random.nextDouble() * 104, 12 + random.nextDouble() * 104,
            .035 + random.nextDouble() * .04) }
        val data = ByteArray(128 * 128 * 2)
        for (y in 0 until 128) for (x in 0 until 128) {
            var signal = .002 + .00010 * sin(x * .71 + y * .31)
            stars.forEach { (sx, sy, amplitude) ->
                signal += amplitude * exp(-((x - sx).pow(2) + (y - sy).pow(2)) / (2 * 1.3 * 1.3))
            }
            val value = (signal * 65535).roundToInt().coerceIn(0, 65535)
            val offset = (y * 128 + x) * 2
            data[offset] = value.toByte()
            data[offset + 1] = (value ushr 8).toByte()
        }
        return FrameData(data, 128, 128, PixelFormat.MONO16, 1, 100_000)
    }

    private fun key(side: Int = 256) = StackSessionKey("live-budget-fixture", side, side, PixelFormat.MONO16,
        Roi(0, 0, side, side), 1, 1, 100_000.0, 0f, "none", "normal")

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(10)
    }
}
