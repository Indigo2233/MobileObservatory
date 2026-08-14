package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoExposureControllerTest {

    @Test
    fun `does not treat 12 bit midtones as clipped`() {
        val camera = RecordingCamera()
        val controller = AutoExposureController().apply {
            mode = AutoExposureMode.CONTINUOUS
            adjustExposure = false
            adjustGain = true
        }
        val data = ByteArray(8)
        repeat(4) { index ->
            data[index * 2] = 0
            data[index * 2 + 1] = 4
        }

        controller.processFrame(
            FrameData(data, 2, 2, PixelFormat.MONO12, frameId = 1L, timestamp = 0L),
            camera
        )

        assertEquals(0, camera.gainWriteCount)
    }

    private class RecordingCamera : Camera {
        override val isOpen: StateFlow<Boolean> = MutableStateFlow(true)
        override val isCapturing: StateFlow<Boolean> = MutableStateFlow(false)
        override val cameraInfo: CameraInfo? = null
        override val exposureRange = FloatRange(1f, 1_000_000f, 10_000f)
        override val gainRange = FloatRange(0f, 20f, 10f)
        override val currentExposureUs = 10_000f
        override var currentGain = 10f
            private set
        override val currentPixelFormat = PixelFormat.MONO12
        override val supportedPixelFormats = listOf(PixelFormat.MONO12)
        override val currentRoi = Roi(0, 0, 2, 2)
        override val cropInfo = CropInfo(0, 0, 2, 2)
        override val hwExposureMaxUs = 1_000_000f
        override var longExposureEnabled = false
        var gainWriteCount = 0
            private set

        override fun close() = Unit
        override fun startCapture(callback: FrameCallback) = Unit
        override fun stopCapture() = Unit
        override fun setExposureTime(us: Float) = Unit
        override fun setGain(value: Float) {
            currentGain = value
            gainWriteCount++
        }
        override fun setPixelFormat(format: PixelFormat) = Unit
        override fun setRoi(roi: Roi) = Unit
        override fun resetRoi() = Unit
        override fun recycleBuffer(buf: ByteArray) = Unit
    }
}
