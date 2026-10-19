package com.indigo.mobileobservatory.stacking

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise real VM callbacks with a camera that deliberately returns an old queued frame. */
@RunWith(AndroidJUnit4::class)
class ScienceCaptureProvenanceInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }

    @Test fun exposureChangeBlocksImmediateStackAndOldSdkFramesCannotTakeTheNewExposure() = runBlocking {
        workspace { vm, camera ->
            val oldRun = camera.scienceCaptureGeneration!!
            camera.emit(oldRun)
            assertNull(vm.liveStackStartIssue())
            main { vm.setExposure(2_000_000f); vm.startLiveStack() }
            assertEquals(LiveStackPhase.EMPTY, vm.stacking.live.state.value.phase)
            assertEquals("settings_busy", vm.liveStackStartIssue())
            camera.emit(oldRun)
            assertFalse(vm.scienceCaptureReady.value)
            camera.allowExposure.countDown()
            await { camera.starts >= 2 && camera.scienceCaptureGeneration != null && vm.exposureUs.value == 2_000_000f }
            await { camera.emit(camera.scienceCaptureGeneration!!); vm.scienceCaptureReady.value }
            main { vm.startLiveStack() }
            assertEquals(LiveStackPhase.RUNNING, vm.stacking.live.state.value.phase)
            assertEquals(2_000_000.0, vm.stacking.live.state.value.key!!.exposureUs, 0.0)
            camera.emit(oldRun)
            assertEquals("Late SDK frame is never counted under the new exposure", 0L, vm.stacking.live.state.value.received)
            assertEquals(0.0, vm.stacking.live.state.value.exposureSeconds, 0.0)
            assertEquals(LiveStackPhase.RUNNING, vm.stacking.live.state.value.phase)
        }
    }

    @Test fun recordingWaitsForExposureTransitionAndThenRetainsTheVerifiedSettings() = runBlocking {
        workspace { vm, camera ->
            val oldRun = camera.scienceCaptureGeneration!!
            camera.emit(oldRun)
            main { vm.setRecordFormat("SER"); vm.setExposure(2_000_000f); vm.startRecording() }
            assertFalse(vm.isRecording.value)
            assertNull(vm.getActiveRecordingFile())
            camera.allowExposure.countDown()
            await { camera.starts >= 2 && camera.scienceCaptureGeneration != null && vm.exposureUs.value == 2_000_000f }
            await { camera.emit(camera.scienceCaptureGeneration!!); vm.scienceCaptureReady.value }
            main { vm.startRecording() }
            assertTrue(vm.isRecording.value)
            val file = checkNotNull(vm.getActiveRecordingFile())
            try {
                camera.emit(oldRun)
                camera.emit(camera.scienceCaptureGeneration!!)
                main { vm.stopRecording() }
                await { vm.getActiveRecordingFile() == null }
                val context = JSONObject(File(file.parentFile, "${file.name}.capture.json").readText())
                assertTrue(context.getBoolean("valid"))
                assertEquals(2_000_000.0, context.getJSONObject("capture").getDouble("exposureUs"), 0.0)
            } finally {
                main { vm.stopRecording() }
                file.delete(); File(file.parentFile, "${file.name}.capture.json").delete()
            }
        }
    }

    @Test fun pauseAndResumeDuringControlWriteCannotStartAnUnverifiedReader() = runBlocking {
        workspace { vm, camera ->
            camera.emit(camera.scienceCaptureGeneration!!)
            main { vm.setExposure(2_000_000f) }
            await { camera.writingExposure.count == 0L }
            main { vm.togglePreviewPause(); vm.togglePreviewPause(); vm.resumeCaptureForStacking() }
            assertEquals(1, camera.starts)
            camera.allowExposure.countDown()
            await { camera.starts == 2 && camera.scienceCaptureGeneration != null }
            await { camera.emit(camera.scienceCaptureGeneration!!); vm.scienceCaptureReady.value }
            assertNull(vm.liveStackStartIssue())
            assertEquals(2, camera.starts)
        }
    }

    private suspend fun workspace(block: suspend (CameraViewModel, BufferedCamera) -> Unit) {
        val store = ViewModelStore()
        val app = instrumentation.targetContext.applicationContext as Application
        lateinit var vm: CameraViewModel
        val camera = BufferedCamera()
        main { vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java] }
        try {
            await { vm.stacking.calibration.state.value.initialized }
            main {
                vm.cameraManager.javaClass.getDeclaredField("activeCamera").apply { isAccessible = true }.set(vm.cameraManager, camera)
                vm.resumeCaptureForStacking()
            }
            await { camera.starts >= 1 && camera.scienceCaptureGeneration != null }
            await { camera.emit(camera.scienceCaptureGeneration!!); vm.scienceCaptureReady.value }
            block(vm, camera)
        } finally {
            camera.allowExposure.countDown()
            main { store.clear() }
        }
    }
    private suspend fun await(test: () -> Boolean) = withTimeout(15_000) { while (!test()) delay(20) }

    private class BufferedCamera : Camera {
        override val isOpen = MutableStateFlow(true)
        override val isCapturing = MutableStateFlow(false)
        override val cameraInfo = CameraInfo("Provenance fixture", "isolated", 64, 48, 16)
        override val exposureRange = FloatRange(1f, 5_000_000f, 1_000_000f)
        override val gainRange = FloatRange(0f, 100f, 10f)
        @Volatile override var currentExposureUs = 1_000_000f
        override val currentGain = 10f
        override val currentPixelFormat = PixelFormat.MONO16
        override val supportedPixelFormats = listOf(currentPixelFormat)
        override val currentRoi = Roi(0, 0, 64, 48)
        override val cropInfo = CropInfo(0, 0, 64, 48)
        override val hwExposureMaxUs = 5_000_000f
        override var longExposureEnabled = false
        override val sciencePixelTraits = PixelTraits(alignment = SampleAlignment.RIGHT)
        @Volatile override var scienceCaptureGeneration: Long? = null
        @Volatile var starts = 0
        @Volatile private var callback: FrameCallback? = null
        private var id = 0L
        val allowExposure = CountDownLatch(1)
        val writingExposure = CountDownLatch(1)
        override fun startCapture(callback: FrameCallback) {
            this.callback = callback; starts++; scienceCaptureGeneration = starts.toLong(); isCapturing.value = true
        }
        override fun stopCapture() { scienceCaptureGeneration = null; isCapturing.value = false }
        override fun setExposureTime(us: Float) {
            scienceCaptureGeneration = null
            writingExposure.countDown()
            check(allowExposure.await(10, TimeUnit.SECONDS))
            currentExposureUs = us
        }
        fun emit(run: Long) { callback?.onFrame(FrameData(ByteArray(64*48*2),64,48,currentPixelFormat,++id,System.currentTimeMillis(),run)) }
        override fun close() { stopCapture(); isOpen.value = false }
        override fun setGain(value: Float) {}
        override fun setPixelFormat(format: PixelFormat) {}
        override fun setRoi(roi: Roi) {}
        override fun resetRoi() {}
        override fun recycleBuffer(buf: ByteArray) {}
    }
}
