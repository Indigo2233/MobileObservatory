package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.GainControlKind
import com.indigo.mobileobservatory.camera.PixelFormat
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FITSWriterTest {

    @Test
    fun `writes ISO metadata from gain kind`() {
        val file = Files.createTempFile("indigo-iso", ".fits").toFile()
        try {
            FITSWriter().write(
                file = file,
                frame = FrameData(ByteArray(4), 2, 2, PixelFormat.MONO8, frameId = 1L, timestamp = 0L),
                exposureSeconds = 1f,
                gain = 800f,
                gainKind = GainControlKind.ISO,
                gainLabel = "Native sensitivity"
            )

            val header = file.readBytes().decodeToString()
            assertTrue(header.contains("ISOSPEED"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `does not infer ISO metadata from a display label`() {
        val file = Files.createTempFile("indigo-gain", ".fits").toFile()
        try {
            FITSWriter().write(
                file = file,
                frame = FrameData(ByteArray(4), 2, 2, PixelFormat.MONO8, frameId = 1L, timestamp = 0L),
                exposureSeconds = 1f,
                gain = 100f,
                gainLabel = "ISO"
            )

            val header = file.readBytes().decodeToString()
            assertFalse(header.contains("ISOSPEED"))
        } finally {
            file.delete()
        }
    }
}
