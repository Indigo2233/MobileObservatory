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
            assertEquals("processing_size", c.task.value.message)
            assertEquals(LiveStackPhase.EMPTY, c.live.state.value.phase)
            // Known, small delivered hardware-binned geometry; no BIAS/DARK/FLAT selected.
            val reduced = key.copy(width = 256, height = 256, roi = Roi(0, 0, 256, 256), hardwareBin = 4)
            assertNull(c.liveInputIssue(reduced))
            main { c.startLive(reduced) }
            assertEquals(LiveStackPhase.RUNNING, c.live.state.value.phase)
            assertEquals(0L, c.calibration.memoryBytes)
        } finally { main { c.close() }; scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun largeSerHeaderStaysSelectableAndFailsTheProcessingBudgetRatherThanCalibration() = runBlocking {
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
            await { c.task.value.phase == StackTaskPhase.FAILED }
            assertEquals("memory_budget", c.task.value.message)
            assertEquals(3536, c.task.value.source?.width)
            assertEquals("RGGB", c.task.value.source?.effectiveLayout)
        } finally { main { c.close() }; scope.cancel(); directory.deleteRecursively() }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(20) }
}
