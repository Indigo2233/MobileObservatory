package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SERWriterTest {

    @Test
    fun `updates frame count in the header after each frame`() {
        val file = Files.createTempFile("indigo-ser", ".ser").toFile()
        try {
            val writer = SERWriter(file)
            writer.open(2, 2, PixelFormat.MONO8, "Cam", null)
            writer.writeFrame(FrameData(ByteArray(4) { 1 }, 2, 2, PixelFormat.MONO8, 1L, 0L))
            writer.writeFrame(FrameData(ByteArray(4) { 2 }, 2, 2, PixelFormat.MONO8, 2L, 0L))
            writer.close()

            val bytes = file.readBytes()
            val count = ByteBuffer.wrap(bytes, 38, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals(2, count)
            assertTrue(bytes.size >= 178 + 8)
        } finally {
            file.delete()
        }
    }
}
