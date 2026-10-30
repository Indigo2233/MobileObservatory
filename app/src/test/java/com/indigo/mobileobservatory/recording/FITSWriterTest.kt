package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.GainControlKind
import com.indigo.mobileobservatory.camera.PixelFormat
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FITSWriterTest {

    @Test
    fun `streams Bayer 16 bit samples across chunks with FITS padding`() {
        val count = 70_001
        val samples = ByteArray(count * 2)
        for (i in 0 until count) {
            val value = (i * 997) and 0xffff
            samples[i * 2] = value.toByte()
            samples[i * 2 + 1] = (value ushr 8).toByte()
        }
        val file = Files.createTempFile("indigo-stream16", ".fits").toFile()
        try {
            FITSWriter().write(file, FrameData(samples, count, 1, PixelFormat.BAYER_RG16, 1L, 0L), 1f, 0f)
            val bytes = file.readBytes()
            val header = bytes.copyOfRange(0, 2880).toString(Charsets.US_ASCII)
            assertTrue(header.contains("BAYERPAT"))
            assertTrue(header.contains("'RGGB'"))
            assertTrue(header.contains("BZERO"))
            assertEquals(0, bytes.size % 2880)
            for (i in 0 until count) {
                val signed = (((bytes[2880 + i * 2].toInt() and 0xff) shl 8) or
                    (bytes[2880 + i * 2 + 1].toInt() and 0xff)).toShort().toInt()
                assertEquals((i * 997) and 0xffff, signed + 32768)
            }
            for (i in 2880 + samples.size until bytes.size) assertEquals(0, bytes[i].toInt())
        } finally { file.delete() }
    }

    @Test
    fun `writes 8 bit samples and zero padding`() {
        val samples = ByteArray(70_001) { (it * 37).toByte() }
        val file = Files.createTempFile("indigo-stream8", ".fits").toFile()
        try {
            FITSWriter().write(file, FrameData(samples, samples.size, 1, PixelFormat.MONO8, 1L, 0L), 1f, 0f)
            val bytes = file.readBytes()
            assertEquals(0, bytes.size % 2880)
            org.junit.Assert.assertArrayEquals(samples, bytes.copyOfRange(2880, 2880 + samples.size))
            for (i in 2880 + samples.size until bytes.size) assertEquals(0, bytes[i].toInt())
        } finally { file.delete() }
    }

    @Test
    fun `promotes 8 bit input with configured 16 bit format`() {
        val file = Files.createTempFile("indigo-promote", ".fits").toFile()
        try {
            val frame = FrameData(byteArrayOf(0, 1, 127, -1), 4, 1, PixelFormat.MONO8, 1L, 0L)
            FITSWriter().write(file, frame, 1f, 0f, configuredFormat = PixelFormat.MONO16)
            val bytes = file.readBytes()
            val expected = byteArrayOf(-128, 0, -127, 0, -1, 0, 127, 0)
            org.junit.Assert.assertArrayEquals(expected, bytes.copyOfRange(2880, 2888))
        } finally { file.delete() }
    }

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

    @Test
    fun `writes the sequence image type`() {
        val file = Files.createTempFile("indigo-dark", ".fits").toFile()
        try {
            FITSWriter().write(
                file = file,
                frame = FrameData(ByteArray(4), 2, 2, PixelFormat.MONO8, frameId = 1L, timestamp = 0L),
                exposureSeconds = 1f,
                gain = 100f,
                imageType = "dark"
            )

            val header = file.readBytes().decodeToString()
            assertTrue(header.contains("IMAGETYP"))
            assertTrue(header.contains("'DARK'"))
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
