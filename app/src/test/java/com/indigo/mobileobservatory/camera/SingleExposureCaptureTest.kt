package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SingleExposureCaptureTest {
    @Test fun nativeSingleFrameStopsAndOwnsItsBytesBeforeReturning(): Unit = runTest {
        val camera = SingleCamera()
        val task = async { captureSingleExposureFrame(camera, 1000, { currentTime }) }
        runCurrent()
        assertEquals(1, camera.singleStarts); assertEquals(0, camera.streamStarts)
        val source = byteArrayOf(1, 2, 3, 4)
        camera.emit(source)
        // Native single exposure can become idle immediately after its callback.
        camera.isCapturing.value = false
        source.fill(99)
        val result = checkNotNull(task.await())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.frame.data)
        assertEquals(1, camera.stops); assertTrue(camera.isCaptureStopped)
        val late = byteArrayOf(5, 6, 7, 8)
        camera.emit(late)
        assertEquals(2, camera.recycled.size)
        assertSame(source, camera.recycled[0]); assertSame(late, camera.recycled[1])
        assertEquals(1, camera.singleStarts)
    }

    @Test fun streamAdapterIsStoppedAfterOneAcceptedFrame(): Unit = runTest {
        val camera = StreamCamera()
        val task = async { captureSingleExposureFrame(camera, 1000, { currentTime }) }
        runCurrent(); camera.emit(byteArrayOf(1,2,3,4))
        assertNotNull(task.await())
        assertEquals(1, camera.streamStarts); assertEquals(1, camera.stops)
        assertTrue(camera.isCaptureStopped)
    }

    @Test fun cancelledExposureStopsAndRejectsLateCallbacks(): Unit = runTest {
        val camera = SingleCamera()
        val task = async { captureSingleExposureFrame(camera, 1000, { currentTime }) }
        runCurrent(); task.cancelAndJoin()
        assertTrue(camera.isCaptureStopped); assertEquals(1, camera.stops)
        camera.emit(byteArrayOf(1,2,3,4))
        assertEquals(1, camera.recycled.size)
        assertEquals(1, camera.singleStarts)
    }

    @Test fun timeoutStopsWithoutStartingAnotherExposure(): Unit = runTest {
        val camera = SingleCamera()
        assertNull(captureSingleExposureFrame(camera, 1000, { currentTime }))
        assertEquals(1000L, currentTime)
        assertEquals(1, camera.singleStarts); assertEquals(1, camera.stops)
    }

    @Test fun stopFailurePreventsAResultFromReachingTheSolver(): Unit = runTest {
        val camera = SingleCamera().apply { confirmStop = false }
        val task = async { runCatching { captureSingleExposureFrame(camera, 1000, { currentTime }) } }
        runCurrent(); camera.emit(byteArrayOf(1,2,3,4))
        val failure = task.await().exceptionOrNull()
        assertEquals("Camera acquisition could not be stopped", failure?.message)
        assertEquals(1, camera.stops)
    }

    @Test fun connectionOrSettingsFailureAlwaysStopsTheAcquisition(): Unit = runTest {
        val camera = SingleCamera()
        var connected = true
        val task = async { runCatching { captureSingleExposureFrame(camera, 2000, { currentTime },
            checkCamera = { check(connected) { "USB disconnected" } }) } }
        runCurrent(); connected = false
        advanceTimeBy(1000); runCurrent()
        assertEquals("USB disconnected", task.await().exceptionOrNull()?.message)
        assertTrue(camera.isCaptureStopped)
    }

    @Test fun staleFramesAreRecycledUntilAFreshFrameArrives(): Unit = runTest {
        val camera = SingleCamera()
        val task = async { captureSingleExposureFrame(camera, 1000, { currentTime }, acceptFrame = { it.frameId > 1 }) }
        runCurrent(); camera.emit(byteArrayOf(1,1,1,1)); camera.emit(byteArrayOf(2,2,2,2))
        assertArrayEquals(byteArrayOf(2,2,2,2), checkNotNull(task.await()).frame.data)
        assertEquals(2, camera.recycled.size); assertEquals(1, camera.singleStarts)
    }

    @Test fun startupFailureStillStopsTheCamera(): Unit = runTest {
        val camera = object : StreamCamera() {
            override fun startCapture(callback: FrameCallback) { super.startCapture(callback); error("SDK startup failed") }
        }
        val failure = runCatching { captureSingleExposureFrame(camera, 1000, { currentTime }) }.exceptionOrNull()
        assertEquals("SDK startup failed", failure?.message)
        assertTrue(camera.isCaptureStopped)
    }

    @Test fun readerExitWithoutAnImageStillRequiresSdkStop(): Unit = runTest {
        val camera = SingleCamera()
        val task = async { runCatching { captureSingleExposureFrame(camera, 2000, { currentTime }) } }
        runCurrent()
        camera.isCapturing.value = false
        assertFalse(camera.isCaptureStopped)
        advanceTimeBy(1000); runCurrent()
        assertTrue(task.await().exceptionOrNull()?.message.orEmpty().contains("stopped before delivering an image"))
        assertEquals(1, camera.stops)
        assertTrue(camera.isCaptureStopped)
        assertEquals(1, camera.singleStarts)
    }

    private open class StreamCamera : Camera {
        override val isOpen = MutableStateFlow(true)
        override val isCapturing = MutableStateFlow(false)
        override val cameraInfo: CameraInfo? = null
        override val exposureRange = FloatRange(1f, 1_000_000f, 1000f)
        override val gainRange = FloatRange(0f,100f,10f)
        override val currentExposureUs = 1000f
        override val currentGain = 10f
        override val currentPixelFormat = PixelFormat.MONO8
        override val supportedPixelFormats = listOf(PixelFormat.MONO8)
        override val currentRoi = Roi(0,0,2,2)
        override val cropInfo = CropInfo(0,0,2,2)
        override val hwExposureMaxUs = 1_000_000f
        override var longExposureEnabled = false
        var streamStarts = 0; var stops = 0; var confirmStop = true
        private var stopConfirmed = true
        override val isCaptureStopped get() = !isCapturing.value && stopConfirmed
        protected var callback: FrameCallback? = null
        private var sequence = 0L
        val recycled = mutableListOf<ByteArray>()
        protected fun begin(callback: FrameCallback) { this.callback = callback; stopConfirmed = false; isCapturing.value = true }
        override fun startCapture(callback: FrameCallback) { streamStarts++; begin(callback) }
        override fun stopCapture() { stops++; isCapturing.value = false; stopConfirmed = confirmStop }
        fun emit(bytes: ByteArray) { callback!!.onFrame(FrameData(bytes,2,2,PixelFormat.MONO8,++sequence,0)) }
        override fun recycleBuffer(buf: ByteArray) { recycled += buf }
        override fun close() { isOpen.value = false }
        override fun setExposureTime(us: Float) {}
        override fun setGain(value: Float) {}
        override fun setPixelFormat(format: PixelFormat) {}
        override fun setRoi(roi: Roi) {}
        override fun resetRoi() {}
    }
    private class SingleCamera : StreamCamera(), CameraSingleExposureCapable {
        var singleStarts = 0
        override fun startSingleExposure(callback: FrameCallback) { singleStarts++; begin(callback) }
    }
}
