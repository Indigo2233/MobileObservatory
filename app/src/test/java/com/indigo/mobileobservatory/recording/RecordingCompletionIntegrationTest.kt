package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.stacking.CalibrationRecordingContext
import com.indigo.mobileobservatory.stacking.StackSessionKey
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class RecordingCompletionIntegrationTest {
    @Test fun onlyCompletedSerAndPserSessionsPublishValidCaptureContext() {
        val folder = Files.createTempDirectory("recording-provenance").toFile()
        try {
            for (packed in listOf(false, true)) for (fault in listOf("none", "write", "drain", "close")) {
                val file = File(folder, "$packed-$fault.${if (packed) "pser" else "ser"}")
                val key = StackSessionKey("test camera", 8, 6, PixelFormat.BAYER_RG12, Roi(0, 0, 8, 6),
                    1, 1, 250_000.0, 20f, "L", "raw")
                CalibrationRecordingContext.invalidate(file, "recording_in_progress")
                val ser = if (packed) null else SERWriter(file).also { it.open(8, 6, key.format) }
                val pser = if (packed) PSERWriter(file).also { it.open(8, 6, key.format) } else null
                val queue = LinkedBlockingQueue<FrameData>()
                repeat(3) { frame ->
                    val bytes = ByteArray(8 * 6 * 2)
                    repeat(48) { pixel ->
                        val value = 1300 + pixel * 13 + frame
                        bytes[pixel * 2] = value.toByte(); bytes[pixel * 2 + 1] = (value ushr 8).toByte()
                    }
                    queue.add(FrameData(bytes, 8, 6, key.format, frame.toLong(), 0))
                }
                val released = AtomicInteger()
                lateinit var session: RecordingWriteSession<FrameData>
                session = RecordingWriteSession(queue, write = { frame ->
                    if (frame.frameId == 1L && fault in listOf("write", "drain")) throw IOException("full volume")
                    ser?.writeFrame(frame); pser?.writeFrame(frame)
                    if (frame.frameId == 2L) session.requestStop()
                }, release = { released.incrementAndGet() }, closeWriter = {
                    ser?.close(); pser?.close()
                    if (fault == "close") throw IOException("failed final sync")
                }, onFinished = { outcome ->
                    assertFalse(ser?.isOpen ?: pser!!.isOpen)
                    if (outcome.succeeded) CalibrationRecordingContext.writeForRecording(file, key)
                    else CalibrationRecordingContext.invalidate(file, outcome.failure!!)
                })
                if (fault == "drain") session.requestStop()
                session.start()
                assertTrue(session.awaitFinished(5000))
                assertEquals(3, released.get())
                val context = JSONObject(File(folder, "${file.name}.capture.json").readText())
                assertEquals("$packed/$fault", fault == "none", context.getBoolean("valid"))
                if (fault == "none") {
                    assertEquals(250_000.0, CalibrationRecordingContext.readForRecording(file)!!.exposureUs!!, 0.0)
                    SERReader(file).use { reader ->
                        assertTrue(reader.open()); assertEquals(3, reader.header!!.frameCount)
                        assertEquals(1302f / 4095f, reader.readSensorFrame(2)!!.pixels[0], 0f)
                    }
                } else {
                    try { CalibrationRecordingContext.readForRecording(file); fail("Damaged capture must reject manual unknown confirmation") }
                    catch (expected: IllegalArgumentException) { assertEquals("calibration_capture_changed", expected.message) }
                }
                session.requestStop(); assertTrue(session.awaitFinished(100))
                assertEquals(fault == "none", JSONObject(File(folder, "${file.name}.capture.json").readText()).getBoolean("valid"))
            }
        } finally { folder.deleteRecursively() }
    }
}
