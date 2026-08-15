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

    @Test
    fun `does not treat left aligned 12 bit midtones as clipped`() {
        val camera = RecordingCamera()
        val controller = AutoExposureController().apply {
            mode = AutoExposureMode.CONTINUOUS
            adjustExposure = false
            adjustGain = true
        }
        val data = ByteArray(8)
        repeat(4) { index ->
            data[index * 2] = 0
            data[index * 2 + 1] = 0x40
        }

        controller.processFrame(
            FrameData(data, 2, 2, PixelFormat.MONO12, frameId = 1L, timestamp = 0L),
            camera
        )

        assertEquals(0, camera.gainWriteCount)
    }

    @Test
    fun `iso camera steps to the next legal value when underexposed`() {
        val camera = RecordingCamera(
            gainCapability = GainValueNormalizer.isoCapability(
                allowedValues = listOf(100f, 200f, 400f, 800f, 1600f),
                current = 400f
            ),
            initialGain = 400f
        )
        val controller = AutoExposureController().apply {
            mode = AutoExposureMode.CONTINUOUS
            adjustExposure = false
            adjustGain = true
        }

        controller.processFrame(darkMono8(), camera)

        assertEquals(800f, camera.currentGain, 0f)
        assertEquals(1, camera.gainWriteCount)
    }

    @Test
    fun `stops raising gain at the device maximum`() {
        val camera = RecordingCamera(initialGain = 20f)
        val controller = AutoExposureController().apply {
            mode = AutoExposureMode.CONTINUOUS
            adjustExposure = false
            adjustGain = true
        }

        controller.processFrame(darkMono8(), camera)

        assertEquals(20f, camera.currentGain, 0f)
        assertEquals(0, camera.gainWriteCount)
    }

    @Test
    fun `undocumented adapters can hold gain while exposure still adjusts`() {
        val camera = object : RecordingCamera(initialGain = 10f) {
            override fun adjustGainForExposure(stops: Float): Float = currentGain
        }
        val controller = AutoExposureController().apply {
            mode = AutoExposureMode.CONTINUOUS
            adjustExposure = false
            adjustGain = true
        }

        controller.processFrame(darkMono8(), camera)

        assertEquals(10f, camera.currentGain, 0f)
        assertEquals(0, camera.gainWriteCount)
    }

    @Test
    fun `zwo style stop mapping increases native gain`() {
        val camera = object : RecordingCamera(
            gainCapability = GainCapability(min = 0f, max = 600f, step = 1f, defaultValue = 100f),
            initialGain = 100f
        ) {
            override fun adjustGainForExposure(stops: Float): Float =
                GainValueNormalizer.normalize(
                    gainCapability,
                    currentGain + GainConversions.zwoStopsToNative(stops)
                )
        }
        val controller = AutoExposureController().apply {
            mode = AutoExposureMode.CONTINUOUS
            adjustExposure = false
            adjustGain = true
        }

        controller.processFrame(darkMono8(), camera)

        assertEquals(130f, camera.currentGain, 0f)
    }

    @Test
    fun `rgb24 midtones are not treated as high bit clipped values`() {
        val camera = RecordingCamera(
            gainCapability = GainValueNormalizer.isoCapability(
                allowedValues = listOf(100f, 200f, 400f),
                current = 200f
            ),
            initialGain = 200f
        )
        val controller = AutoExposureController().apply {
            mode = AutoExposureMode.CONTINUOUS
            adjustExposure = false
            adjustGain = true
        }
        val data = ByteArray(12) { 70 }
        controller.processFrame(
            FrameData(data, 2, 2, PixelFormat.RGB24, frameId = 1L, timestamp = 0L),
            camera
        )
        assertEquals(200f, camera.currentGain, 0f)
        assertEquals(0, camera.gainWriteCount)
    }

    private fun darkMono8(): FrameData =
        FrameData(ByteArray(4), 2, 2, PixelFormat.MONO8, frameId = 1L, timestamp = 0L)

    private open class RecordingCamera(
        override var gainCapability: GainCapability = GainCapability(min = 0f, max = 20f, defaultValue = 10f),
        initialGain: Float = 10f
    ) : Camera {
        override val isOpen: StateFlow<Boolean> = MutableStateFlow(true)
        override val isCapturing: StateFlow<Boolean> = MutableStateFlow(false)
        override val cameraInfo: CameraInfo? = null
        override val exposureRange = FloatRange(1f, 1_000_000f, 10_000f)
        override val gainRange = FloatRange(0f, 20f, 10f)
        override val currentExposureUs = 10_000f
        override var currentGain = initialGain
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
