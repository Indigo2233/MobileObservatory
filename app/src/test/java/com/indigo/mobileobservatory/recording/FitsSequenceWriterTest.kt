package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitsSequenceWriterTest {

    @Test
    fun `writes each frame as its own FITS file without close`() {
        val dir = Files.createTempDirectory("indigo-fits-seq").toFile()
        try {
            val writer = FitsSequenceWriter(dir)
            writer.exposureSeconds = 0.01f
            writer.gain = 100f
            writer.open()
            writer.writeFrame(FrameData(ByteArray(4) { 12 }, 2, 2, PixelFormat.MONO8, 1L, 0L))
            writer.writeFrame(FrameData(ByteArray(4) { 34 }, 2, 2, PixelFormat.MONO8, 2L, 0L))

            val first = File(dir, "frame_000001.fits")
            val second = File(dir, "frame_000002.fits")
            assertTrue(first.isFile)
            assertTrue(second.isFile)
            assertEquals(2, writer.currentFrameCount)
            val header = first.readBytes().decodeToString()
            assertTrue(header.contains("SIMPLE"))
            assertTrue(header.contains("END"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `keeps completed frames if the session is abandoned`() {
        val dir = Files.createTempDirectory("indigo-fits-crash").toFile()
        try {
            val writer = FitsSequenceWriter(dir)
            writer.open()
            writer.writeFrame(FrameData(ByteArray(4) { 7 }, 2, 2, PixelFormat.MONO8, 1L, 0L))
            val surviving = File(dir, "frame_000001.fits")
            assertTrue(surviving.isFile)
            assertTrue(surviving.length() > 0)
            assertTrue(surviving.readBytes().decodeToString().contains("SIMPLE"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
