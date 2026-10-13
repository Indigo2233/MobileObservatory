package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

class RecordingWriterFailureTest {
    @Test fun serTrailerFailureStillClosesItsFileAndRemainsIdempotentlyClosed() = withFile { file ->
        val writer = SERWriter(file)
        writer.open(4, 4, PixelFormat.MONO16)
        writer.writeFrame(FrameData(ByteArray(32), 4, 4, PixelFormat.MONO16, 1, 0))
        val failed = replaceFileWithFailingTrailer(writer, file)
        try { writer.close(); fail("Trailer failure must propagate") } catch (_: IOException) { }
        assertFalse(failed.fd.valid()); assertFalse(writer.isOpen)
        writer.close()
    }

    @Test fun pserTrailerFailureStillClosesItsFileAndRemainsIdempotentlyClosed() = withFile { file ->
        val writer = PSERWriter(file)
        writer.open(4, 4, PixelFormat.MONO12)
        writer.writeFrame(FrameData(ByteArray(32), 4, 4, PixelFormat.MONO12, 1, 0))
        val failed = replaceFileWithFailingTrailer(writer, file)
        try { writer.close(); fail("Trailer failure must propagate") } catch (_: IOException) { }
        assertFalse(failed.fd.valid()); assertFalse(writer.isOpen)
        writer.close()
    }

    private fun replaceFileWithFailingTrailer(writer: Any, file: File): RandomAccessFile {
        val field = writer.javaClass.getDeclaredField("raf").apply { isAccessible = true }
        (field.get(writer) as RandomAccessFile).close()
        val failed = object : RandomAccessFile(file, "rw") {
            override fun write(bytes: ByteArray) { throw IOException("simulated trailer storage failure") }
        }
        field.set(writer, failed)
        return failed
    }

    private fun withFile(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("recording-close-failure").toFile()
        try { test(File(directory, "test.ser")) } finally { directory.deleteRecursively() }
    }
}
