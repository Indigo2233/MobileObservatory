package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GuideAcquisitionTest {
    private class CameraFixture : Camera {
        override val isOpen = MutableStateFlow(true)
        override val isCapturing = MutableStateFlow(true)
        override val cameraInfo = CameraInfo("Guide fixture", "guide", 64, 64, 16)
        override val exposureRange = FloatRange(1000f, 5000000f, 1000000f)
        override val gainRange = FloatRange(0f, 100f, 0f)
        override var currentExposureUs = 1000000f
        override val currentGain = 0f
        override val currentPixelFormat = PixelFormat.MONO16
        override val supportedPixelFormats = listOf(PixelFormat.MONO16)
        override val currentRoi = Roi(0, 0, 64, 64)
        override val cropInfo = CropInfo(0, 0, 64, 64)
        override val hwExposureMaxUs = 5000000f
        override var longExposureEnabled = false
        var starts = 0; var stops = 0; var refusesStop = false
        var onStart: () -> Unit = {}
        override fun startCapture(callback: FrameCallback) { starts++; isCapturing.value = true; onStart() }
        override fun stopCapture() { stops++; if (!refusesStop) isCapturing.value = false }
        override fun close() { isOpen.value = false; isCapturing.value = false }
        override fun setExposureTime(us: Float) { currentExposureUs = us }
        override fun setGain(value: Float) = Unit
        override fun setPixelFormat(format: PixelFormat) = Unit
        override fun setRoi(roi: Roi) = Unit
        override fun resetRoi() = Unit
        override fun recycleBuffer(buf: ByteArray) = Unit
    }
    private val callback = object : FrameCallback { override fun onFrame(frame: FrameData) = Unit }

    @Test fun stoppedExposureStaysStoppedAfterParameterChanges() = runTest {
        val camera = CameraFixture(); var retired = 0; var published = true
        val acquisition = GuideAcquisition(Mutex(), { camera }, { it.startCapture(callback) }, { retired++ }, { published = it })
        acquisition.reconcile(camera)
        acquisition.update(camera) { it!!.setExposureTime(500000f) }
        assertFalse(camera.isCapturing.value); assertFalse(published)
        assertEquals(0, camera.starts); assertEquals(500000f, camera.currentExposureUs, .01f)
        assertEquals(2, retired)
    }

    @Test fun stopDuringParameterWritePreventsTheQueuedRestart() = runTest {
        val camera = CameraFixture()
        val acquisition = GuideAcquisition(Mutex(), { camera }, { it.startCapture(callback) }, {}, {})
        acquisition.requested = true
        acquisition.update(camera) { assertFalse(it!!.isCapturing.value); acquisition.requested = false; it.setExposureTime(250000f) }
        assertFalse(camera.isCapturing.value); assertEquals(0, camera.starts)
    }

    @Test fun runningExposureRestartsOnlyAfterACompletedSettingWrite() = runTest {
        val camera = CameraFixture()
        val acquisition = GuideAcquisition(Mutex(), { camera }, { it.startCapture(callback) }, {}, {})
        acquisition.requested = true
        acquisition.update(camera) { assertFalse(it!!.isCapturing.value); it.setExposureTime(250000f) }
        assertTrue(camera.isCapturing.value); assertEquals(1, camera.starts)
    }

    @Test fun stopWhileTheSdkIsStartingStillStopsThatCapture() = runTest {
        val camera = CameraFixture().apply { isCapturing.value = false }
        val acquisition = GuideAcquisition(Mutex(), { camera }, { it.startCapture(callback) }, {}, {})
        acquisition.requested = true; camera.onStart = { acquisition.requested = false }
        acquisition.reconcile(camera)
        assertFalse(camera.isCapturing.value); assertEquals(1, camera.stops)
    }

    @Test fun cameraReplacementCannotRestartOrWriteTheOldSession() = runTest {
        val camera = CameraFixture(); var current: Camera? = camera; var active = false
        val acquisition = GuideAcquisition(Mutex(), { current }, { it.startCapture(callback) }, {}, { active = it })
        acquisition.requested = true
        acquisition.update(camera) { current = CameraFixture() }
        assertEquals(0, camera.starts)
        assertTrue(active)
        var writes = 0
        assertFalse(acquisition.update(camera) { writes++ }); assertEquals(0, writes)
    }

    @Test fun failedPhysicalStopPreventsWritesAndKeepsTheRunningStateVisible() = runTest {
        val camera = CameraFixture().apply { refusesStop = true }; var active = false; var writes = 0
        val acquisition = GuideAcquisition(Mutex(), { camera }, {}, {}, { active = it })
        try { acquisition.update(camera) { writes++ }; fail("Stop failure was ignored") } catch (_: IllegalStateException) { }
        assertTrue(active); assertEquals(0, writes)
    }
}
