package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.recording.RawRecordingNormalizer
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SERWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** One camera callback is independently owned by preview, recording and the live worker. */
@OptIn(ExperimentalCoroutinesApi::class)
class RawCaptureFanoutIntegrationTest {
    @Test fun previewRecyclingCannotCorruptRecordingOrDelayedLiveDecode() = runTest {
        val format = PixelFormat.MONO12
        val traits = CameraScienceLayouts.zwo(format)!!
        val key = StackSessionKey("ZWO fixture", 2, 2, format, Roi(0, 0, 2, 2), 1, 1, 500_000.0, 10f, "L", "raw", pixelTraits = traits)
        val values = intArrayOf(7, 101, 2049, 4095)
        val bytes = ByteArray(8).also { out -> values.forEachIndexed { i, value ->
            out[2 * i] = (value shl 4).toByte(); out[2 * i + 1] = (value ushr 4).toByte()
        } }
        val callback = FrameData(bytes, 2, 2, format, 51, 1_000_000)
        var accepted: LinearImage? = null
        val engine = object : LiveStackEngine {
            override fun add(image: LinearImage, exposureSeconds: Double, cancelled: () -> Boolean): LiveFrameOutcome {
                assertEquals(.5, exposureSeconds, 0.0)
                accepted = image.deepCopy()
                return LiveFrameOutcome(true, 12, .01, 0.0)
            }
            override fun snapshot(state: LiveStackState) = accepted?.let { LiveStackSnapshot(it.deepCopy(), FloatArray(4) { 1f }, state) }
        }
        val session = LiveStackSession(this, { engine }, { frame, sessionKey -> LinearPixelDecoder.decode(frame, sessionKey.pixelTraits) }, {})
        val recycled = AtomicInteger()
        val preview = PreviewPipeline(backgroundScope, FrameProcessor(), 20)
        val directory = Files.createTempDirectory("raw-capture-fanout").toFile()
        val file = java.io.File(directory, "capture.ser")
        val writer = SERWriter(file)
        try {
            session.start(key)
            // A paused renderer is real PreviewPipeline backpressure; submit the next frame to evict
            // this one synchronously. No Android bitmap implementation is needed for this boundary.
            preview.start(paused = { true }, recycleBuffer = { it.fill(0x5A); recycled.incrementAndGet() })
            session.offer(callback, key)
            val recordingOwned = callback.copy(data = callback.data.copyOf())
            preview.submit(callback)
            preview.submit(callback.copy(data = ByteArray(8), frameId = 52))
            assertEquals(1, recycled.get())
            assertTrue(callback.data.all { it == 0x5A.toByte() })
            assertNull(accepted) // Worker has not decoded anything yet.
            preview.close()

            // The writer consumes its private copy only AFTER the SDK/preview bytes were destroyed.
            val canonical = RawRecordingNormalizer.normalize(recordingOwned, traits)
            writer.open(2, 2, canonical.pixelFormat)
            writer.writeFrame(canonical)
            writer.close()
            recordingOwned.data.fill(0)
            canonical.data.fill(0)
            runCurrent()
            session.pause(); runCurrent()
            assertEquals(1L, session.state.value.accepted)
            assertEquals(.5, session.state.value.exposureSeconds, 0.0)
            SERReader(file).use { reader ->
                assertTrue(reader.open())
                reader.sampleAlignment = SampleAlignment.RIGHT
                assertEquals(1, reader.header!!.frameCount)
                val recorded = reader.readLinearFrame(0)!!
                values.forEachIndexed { index, expected ->
                    assertEquals(expected / 4095f, accepted!!.pixels[index], 0f)
                    assertEquals(expected / 4095f, recorded.pixels[index], 0f)
                }
            }
            assertEquals(2, recycled.get())
        } finally {
            preview.close(); session.close(); runCurrent(); writer.close()
            directory.deleteRecursively()
        }
    }
}
