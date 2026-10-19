package com.indigo.mobileobservatory.stacking

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.recording.SERWriter
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeCaptureAdmissionInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test fun liveFullFrameHasActionableSizeIssueAndSupportedBinnedInputStartsWithoutMasters() = runBlocking {
        val directory = File(app.cacheDir, "stack-admission-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val c = StackingCoordinator(app, scope, calibrationDirectory = directory)
        try {
            await { c.calibration.state.value.initialized && !c.calibration.state.value.busy }
            val key = StackSessionKey("large-camera", 3536, 3536, PixelFormat.BAYER_RG16,
                Roi(0, 0, 3536, 3536), 1, 1, 100_000.0, 100f, "none", "normal")
            main { c.startLive(key) }
            assertEquals("memory_budget", c.task.value.message)
            assertEquals(LiveStackPhase.EMPTY, c.live.state.value.phase)
            // Known, small delivered hardware-binned geometry; no BIAS/DARK/FLAT selected.
            val reduced = key.copy(width = 256, height = 256, roi = Roi(0, 0, 256, 256), hardwareBin = 4)
            assertNull(c.liveInputIssue(reduced))
            main { c.startLive(reduced) }
            assertEquals(LiveStackPhase.RUNNING, c.live.state.value.phase)
            assertEquals(0L, c.calibration.memoryBytes)
        } finally { main { c.close() }; scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun largeSerCanPreviewBeforeScienceAdmissionAndReportsTheOfflineBudget() = runBlocking {
        val directory = File(app.cacheDir, "stack-ser-admission-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val c = StackingCoordinator(app, scope, calibrationDirectory = File(directory, "masters"))
        try {
            val file = File(directory, "full-frame.ser")
            val writer = SERWriter(file)
            writer.open(3536, 3536, PixelFormat.BAYER_RG8)
            writer.writeFrame(FrameData(ByteArray(3536 * 3536), 3536, 3536, PixelFormat.BAYER_RG8, 1, 0))
            writer.close()
            await { c.calibration.state.value.initialized && !c.calibration.state.value.busy }
            main { c.loadFile(file) }
            await { c.task.value.phase == StackTaskPhase.READY }
            assertNotNull(c.sourcePreview.value)
            assertEquals("offline_memory_budget", c.offlinePlan()!!.issue(c.memoryBudgetBytes))
            main { c.analyze(null, false) }
            await { c.task.value.phase == StackTaskPhase.FAILED }
            assertEquals("offline_memory_budget", c.task.value.message)
            assertEquals(3536, c.task.value.source?.width)
            assertEquals("RGGB", c.task.value.source?.effectiveLayout)
        } finally { main { c.close() }; scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun admittedMonoAboveFourMpActuallyReachesTheLiveAccumulator() = runBlocking {
        val directory = File(app.cacheDir, "stack-large-mono-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val c = StackingCoordinator(app, scope, calibrationDirectory = directory)
        try {
            await { c.calibration.state.value.initialized && !c.calibration.state.value.busy }
            val side = 2056
            val key = StackSessionKey("large-mono-fixture", side, side, PixelFormat.MONO8,
                Roi(0, 0, side, side), 1, 4, 100_000.0, 0f, "none", "normal")
            org.junit.Assume.assumeTrue("Device budget cannot admit this fixture", c.liveInputIssue(key) == null)
            main { c.startLive(key) }
            assertEquals(LiveStackPhase.RUNNING, c.live.state.value.phase)
            val pixels = ByteArray(side * side) { 8 }
            for (y in listOf(70, 160, 250, 340, 430)) for (x in listOf(80, 145, 210, 275, 340, 405, 470)) {
                for (dy in -24..24) for (dx in -24..24) {
                    val value = 8 + (190 * kotlin.math.exp(-(dx * dx + dy * dy) / 72.0)).toInt()
                    pixels[(y * 4 + dy) * side + x * 4 + dx] = value.toByte()
                }
            }
            c.acceptFrame(FrameData(pixels, side, side, PixelFormat.MONO8, 1, 0), key)
            await { c.live.state.value.accepted > 0 || c.live.state.value.phase == LiveStackPhase.FAILED || c.live.state.value.rejected > 0 }
            assertEquals(c.live.state.value.toString(), 1L, c.live.state.value.accepted)
            await { c.live.latestSnapshot != null }
            assertEquals(514, c.live.latestSnapshot?.image?.width)
        } finally { main { c.close() }; scope.cancel(); directory.deleteRecursively() }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(20) }
}
