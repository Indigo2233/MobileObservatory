package com.indigo.mobileobservatory.camera

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SimulatedCameraTest {
    private fun next(frames: LinkedBlockingQueue<FrameData>): FrameData =
        requireNotNull(frames.poll(5, TimeUnit.SECONDS)) { "Simulation stopped delivering frames" }

    private fun start(camera: SimulatedCamera): LinkedBlockingQueue<FrameData> =
        LinkedBlockingQueue<FrameData>().also { frames ->
            camera.startCapture(object : FrameCallback { override fun onFrame(frame: FrameData) { frames.add(frame) } })
        }

    @Test fun defaultPreviewIsBayerSoLp750MappingCanBeEnabledImmediately() {
        val camera = SimulatedCamera("Demo")
        try {
            assertTrue(camera.currentPixelFormat.isBayer)
            assertEquals(PixelFormat.BAYER_RG8, camera.currentPixelFormat)
        } finally { camera.close() }
    }

    @Test fun heldFramesAreNeverOverwrittenAndThePoolRemainsBoundedAcrossRestarts() {
        val camera = SimulatedCamera("Demo")
        try {
            val frames = start(camera)
            val held = List(3) { next(frames) }
            val copies = held.map { it.data.copyOf() }
            assertNull(frames.poll(350, TimeUnit.MILLISECONDS))
            held.zip(copies).forEach { (frame, copy) -> assertArrayEquals(copy, frame.data) }
            camera.stopCapture()
            val restarted = start(camera)
            assertNull(restarted.poll(200, TimeUnit.MILLISECONDS))
            camera.recycleBuffer(held[0].data)
            camera.recycleBuffer(held[0].data) // A duplicate or foreign return cannot create another lease.
            camera.recycleBuffer(ByteArray(640 * 480 * 3))
            assertSame(held[0].data, next(restarted).data)
            assertNull(restarted.poll(250, TimeUnit.MILLISECONDS))
        } finally { camera.close() }
    }

    @Test fun nativeCfaTracksOddRoiOffsetsAndNeverClaimsScientificProvenance() {
        val camera = SimulatedCamera("Demo")
        try {
            assertFalse(camera.recordsLiveViewAsScience)
            assertNull(camera.sciencePixelTraits)
            assertNull(camera.scienceCaptureGeneration)
            camera.setPixelFormat(PixelFormat.BAYER_RG8)
            listOf(Triple(0, 0, PixelFormat.BAYER_RG8), Triple(1, 0, PixelFormat.BAYER_GR8),
                Triple(0, 1, PixelFormat.BAYER_GB8), Triple(1, 1, PixelFormat.BAYER_BG8)).forEach { (x, y, format) ->
                camera.setRoi(Roi(x, y, 32, 24))
                val frame = next(start(camera))
                camera.stopCapture()
                assertEquals(32, frame.width); assertEquals(24, frame.height)
                assertEquals(format, frame.pixelFormat)
                assertNull(frame.captureGeneration)
                camera.recycleBuffer(frame.data)
            }
        } finally { camera.close() }
    }

    @Test fun exposureAndGainChangeBrightnessAndInvalidRoisAreRejected() {
        val camera = SimulatedCamera("Demo")
        try {
            camera.setPixelFormat(PixelFormat.RGB24)
            camera.setRoi(Roi(310, 230, 24, 24))
            camera.setExposureTime(100_000f)
            val dim = next(start(camera))
            camera.stopCapture()
            fun brightness(f: FrameData) = (0 until f.width * f.height * 3).sumOf { f.data[it].toInt() and 255 }
            val baseline = brightness(dim)
            camera.recycleBuffer(dim.data)
            camera.setExposureTime(1_000_000f); camera.setGain(200f)
            val bright = next(start(camera))
            assertTrue(brightness(bright) > baseline * 2)
            assertThrows(IllegalArgumentException::class.java) { camera.setRoi(Roi(Int.MAX_VALUE, 0, 16, 16)) }
            assertThrows(IllegalArgumentException::class.java) { camera.setExposureTime(Float.NaN) }
        } finally { camera.close() }
    }

    @Test fun closeStopsDeliveryAndLateRecycleCannotReopenTheSource() {
        val camera = SimulatedCamera("Demo")
        val frames = start(camera)
        val frame = next(frames)
        camera.close()
        frames.clear()
        camera.recycleBuffer(frame.data)
        assertFalse(camera.isOpen.value); assertFalse(camera.isCapturing.value)
        assertNull(frames.poll(250, TimeUnit.MILLISECONDS))
        assertThrows(IllegalStateException::class.java) { start(camera) }
    }
}
