package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.recording.SERWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException

class CalibrationStorageTest {
    private val geometry = CalibrationGeometry(4, 2, "RGGB", 12)
    private val capture = CalibrationCapture("相机 A", 1_000_000.0, 100.0, 12.0, 1, 3, 1, "L", "RAW", -10.25)
    private val key = StackSessionKey("相机 A", 4, 2, PixelFormat.BAYER_BG12, Roi(1, 3, 4, 2), 1, 2,
        1_000_000.0, 100f, "L", "RAW", 12f)
    private fun master() = CalibrationBuilder.build(CalibrationType.BIAS, geometry, capture, 2,
        { LinearImage(4, 2, 1, FloatArray(8) { (it * 13 + 1).toFloat() / 4095 }) }, name = "偏置精度", sourceIdentity = "source.ser")
    private fun rejects(part: String, action: () -> Unit) {
        try { action(); fail("Expected $part") } catch (e: IllegalArgumentException) { assertTrue(e.message, e.message.orEmpty().contains(part)) }
    }
    private fun directory(action: (File) -> Unit) {
        val path = Files.createTempDirectory("calibration-library").toFile()
        try { action(path) } finally { path.deleteRecursively() }
    }

    @Test fun libraryPersistsExactFloatSamplesAndAllMetadataWithNoMutablePixelEscape() = directory { dir ->
        val library = CalibrationLibrary(dir)
        val original = master(); val descriptor = library.save(original)
        assertEquals(listOf(descriptor), library.list())
        val loaded = library.load(descriptor.id)
        rejects("memory_budget") { library.load(descriptor.id, maxBytes = original.memoryBytes - 1) }
        assertEquals(original.memoryBytes, library.load(descriptor.id, maxBytes = original.memoryBytes).memoryBytes)
        assertEquals(descriptor, loaded.descriptor)
        assertEquals(capture, loaded.descriptor.capture)
        assertArrayEquals(original.imageCopy().pixels, loaded.imageCopy().pixels, 0f)
        loaded.imageCopy().pixels.fill(0f)
        assertArrayEquals(original.imageCopy().pixels, library.load(descriptor.id).imageCopy().pixels, 0f)
        rejects("already_exists") { library.save(original) }
        rejects("calibration_id") { library.load("../outside") }
        library.delete(descriptor.id); assertTrue(library.list().isEmpty())
    }

    @Test fun alteredSampleAndTruncatedFileAreRejectedAndPartialFilesNeverAppearInList() = directory { dir ->
        val library = CalibrationLibrary(dir); val descriptor = library.save(master())
        val file = File(dir, "${descriptor.id}.icm")
        val original = file.readBytes()
        val damaged = original.copyOf()
        damaged[damaged.size - 33] = (damaged[damaged.size - 33].toInt() xor 1).toByte()
        file.writeBytes(damaged)
        rejects("corrupt_master") { library.load(descriptor.id) }
        file.writeBytes(original.copyOf(original.size - 1))
        assertTrue(library.list().isEmpty())
        rejects("truncated_master") { library.load(descriptor.id) }
        File(dir, ".ignored.partial").writeBytes(original)
        assertTrue(library.list().isEmpty())
    }

    @Test fun cancellationCleansPartialOutputAndLoadHonorsCancellation() = directory { dir ->
        val library = CalibrationLibrary(dir); var checks = 0
        try {
            library.save(master()) { if (++checks == 2) throw CancellationException("cancel") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(0, dir.listFiles()!!.size) }
        val descriptor = library.save(master())
        try { library.load(descriptor.id) { throw CancellationException("cancel") }; fail("Expected cancellation") }
        catch (_: CancellationException) { assertEquals(1, library.list().size) }
    }

    @Test fun recordingContextSurvivesFinalFrameCountAndIgnoresDisplaySoftwareBin() = directory { dir ->
        val file = File(dir, "recording.ser")
        assertNull(CalibrationRecordingContext.readForRecording(file))
        val writer = SERWriter(file)
        writer.open(4, 2, PixelFormat.BAYER_RG12, key.camera)
        CalibrationRecordingContext.writeForRecording(file, key, -10.25)
        repeat(3) { writer.writeFrame(FrameData(ByteArray(16), 4, 2, PixelFormat.BAYER_RG12, it.toLong(), 0)) }
        writer.close()
        assertEquals(capture, CalibrationRecordingContext.readForRecording(file))
        // Actual layout is canonical RGGB from the file; context retains original sensor ROI.
        val moved = File(dir, "renamed.ser"); assertTrue(file.renameTo(moved))
        assertTrue(File(dir, "recording.ser.capture.json").renameTo(File(dir, "renamed.ser.capture.json")))
        assertEquals(capture, CalibrationRecordingContext.readForRecording(moved))
    }

    @Test fun invalidMixedCorruptOrStaleSidecarCannotBecomeUnknownAndCannotBeConfirmed() = directory { dir ->
        val file = File(dir, "recording.ser")
        val writer = SERWriter(file); writer.open(4, 2, PixelFormat.BAYER_RG12)
        writer.writeFrame(FrameData(ByteArray(16), 4, 2, PixelFormat.BAYER_RG12, 1, 0)); writer.close()
        CalibrationRecordingContext.writeForRecording(file, key)
        val sidecar = File(dir, "recording.ser.capture.json")
        val valid = sidecar.readBytes()
        CalibrationRecordingContext.invalidate(file, "capture_settings_changed")
        rejects("capture_changed") { CalibrationRecordingContext.readForRecording(file) }
        sidecar.writeText("{ incomplete")
        rejects("capture_changed") { CalibrationRecordingContext.readForRecording(file) }
        sidecar.writeBytes(valid)
        RandomAccessFile(file, "rw").use { it.seek(18); it.write(9) } // Changing actual CFA invalidates context.
        rejects("capture_changed") { CalibrationRecordingContext.readForRecording(file) }
        sidecar.delete()
        assertNull(CalibrationRecordingContext.readForRecording(file))
    }
}
