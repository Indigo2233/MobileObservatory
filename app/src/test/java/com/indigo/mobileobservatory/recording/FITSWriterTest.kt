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
            assertFalse(header.contains("GAIN    "))
            assertFalse(header.contains("GAINDB"))
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

    @Test
    fun `writes native GAIN and optional GAINDB without calling it dB`() {
        val file = Files.createTempFile("indigo-qhy", ".fits").toFile()
        try {
            FITSWriter().write(
                file = file,
                frame = FrameData(ByteArray(4), 2, 2, PixelFormat.MONO8, frameId = 1L, timestamp = 0L),
                exposureSeconds = 1f,
                gain = 36f,
                gainLabel = "Gain",
                gainUnit = null,
                gainDbEquivalent = null
            )

            val header = file.readBytes().decodeToString()
            assertTrue(header.contains("GAIN"))
            assertTrue(header.contains("native value"))
            assertFalse(header.contains("gain in dB"))
            assertFalse(header.contains("GAINDB"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `writes GAINDB only when a conversion is supplied`() {
        val file = Files.createTempFile("indigo-zwo", ".fits").toFile()
        try {
            FITSWriter().write(
                file = file,
                frame = FrameData(ByteArray(4), 2, 2, PixelFormat.MONO8, frameId = 1L, timestamp = 0L),
                exposureSeconds = 1f,
                gain = 100f,
                gainLabel = "Gain",
                gainDbEquivalent = 10f
            )

            val header = file.readBytes().decodeToString()
            assertTrue(header.contains("GAINDB"))
            assertTrue(header.contains("10.00"))
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `refuses RGB24 live view frames`() {
        val file = Files.createTempFile("indigo-rgb24", ".fits").toFile()
        try {
            FITSWriter().write(
                file = file,
                frame = FrameData(ByteArray(12), 2, 2, PixelFormat.RGB24, frameId = 1L, timestamp = 0L),
                exposureSeconds = 1f,
                gain = 100f
            )
        } finally {
            file.delete()
        }
    }
}
