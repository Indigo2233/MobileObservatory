package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.media.MediaTrash
import com.indigo.mobileobservatory.recording.FITSWriter
import com.indigo.mobileobservatory.recording.RecordingFrameSelection
import com.indigo.mobileobservatory.recording.SerFrameEditor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FrameFileEditingTest {
    @Test fun rangesMergeAndSplitWithoutAllocatingPerFrame() {
        val total = 2_000_000_000L
        val selection = RecordingFrameSelection(total).exclude(1L..4L)
            .exclude(5L..1_999_999_998L).restore(2L..3L)
        assertEquals(listOf(1L..1L, 4L..1_999_999_998L), selection.excluded)
        assertEquals(4L, selection.count)
        assertEquals(listOf(0L..0L, 2L..3L, 1_999_999_999L..1_999_999_999L), selection.kept)
        assertTrue(selection.isExcluded(4))
        assertFalse(selection.isExcluded(3))
        assertEquals(0L, selection.exclude(0L until total).count)
    }

    @Test fun serAndPackedSerRewriteOnlyTheCountAndRemoveExactFramesAndTimestamps() {
        val directory = Files.createTempDirectory("ser-frame-edit").toFile()
        try {
            for (packed in listOf(false, true)) {
                val file = File(directory, if (packed) "clip.pser" else "clip.ser")
                val fixture = recording(file, packed)
                val selection = RecordingFrameSelection(5).exclude(0L..0L).exclude(2L..3L)
                SerFrameEditor.removeExcluded(file, SerFrameEditor.readLayout(file), selection)
                val expected = fixture.copyOfRange(0, 178)
                ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).putInt(38, 2)
                val size = if (packed) 23 else 30
                val payload = fixture.copyOfRange(178 + size, 178 + size * 2) +
                    fixture.copyOfRange(178 + size * 4, 178 + size * 5)
                val stamps = fixture.copyOfRange(178 + size * 5 + 8, 178 + size * 5 + 16) + fixture.takeLast(8).toByteArray()
                assertArrayEquals(expected + payload + stamps, file.readBytes())
                assertEquals(2L, SerFrameEditor.readLayout(file).frames)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellingARewriteLeavesTheOriginalFileAndNoPartialOutput() {
        val directory = Files.createTempDirectory("ser-frame-edit-cancel").toFile()
        try {
            val file = File(directory, "clip.ser")
            val fixture = recording(file, packed = false)
            val layout = SerFrameEditor.readLayout(file)
            assertThrows(CancellationException::class.java) {
                SerFrameEditor.removeExcluded(file, layout, RecordingFrameSelection(5).exclude(1L..1L),
                    checkCancelled = { throw CancellationException() })
            }
            assertArrayEquals(fixture, file.readBytes())
            assertEquals(listOf("clip.ser"), directory.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }

    @Test fun fitsFramesKeepCommonLinearScaleAndHeaderExposureAfterScreening() {
        val directory = Files.createTempDirectory("fits-stack-source").toFile()
        try {
            for ((index, code) in listOf(1024, 2048, 4096).withIndex()) {
                val pixels = ByteArray(160 * 32 * 2)
                for (offset in pixels.indices step 2) {
                    pixels[offset] = code.toByte()
                    pixels[offset + 1] = (code ushr 8).toByte()
                }
                FITSWriter().write(directory.resolve("frame_00000${index + 1}.fits"),
                    FrameData(pixels, 160, 32, PixelFormat.MONO16, index.toLong(), 0),
                    exposureSeconds = index + 1f, gain = 0f)
            }
            val trash = File(directory, ".trash")
            val excluded = fitsSequenceMembers(directory)[1]
            assertTrue(MediaTrash.moveSequenceFrame(excluded, trash))
            val frames = fitsSequenceMembers(directory)
            assertFalse(excluded.exists())
            assertEquals(2, frames.size)
            FitsSequenceStackSource(directory, frames, "fixture", DemosaicAlgorithm.BILINEAR, null, 2,
                StackTarget.DEEP_SKY) {}.use { source ->
                assertEquals(2L, source.frameCount)
                assertEquals(80, source.width)
                val samples = FloatArray(80 * 16)
                assertSame(samples, source.readFrameInto(1, samples).pixels)
                assertEquals(4096f / 65535f, samples.first(), 0.000001f)
                assertEquals(4096f / 65535f, samples.last(), 0.000001f)
                assertEquals(3.0, source.exposureSeconds(1), 0.0)
                assertEquals(1024f / 65535f, source.readLuminanceInto(0, samples).pixels.first(), 0.000001f)
            }
            val trashed = trash.listFiles()!!.single { it.extension.equals("fits", true) }
            assertTrue(MediaTrash.restore(trashed, directory, listOf(directory)))
            assertTrue(excluded.isFile)
        } finally { directory.deleteRecursively() }
    }

    private fun recording(file: File, packed: Boolean): ByteArray {
        val header = ByteBuffer.allocate(178).order(ByteOrder.LITTLE_ENDIAN)
        header.put((if (packed) "PSER-RECORDER" else "LUCAM-RECORDER").toByteArray())
        header.position(26)
        header.putInt(5).putInt(3).putInt(if (packed) 12 else 16).putInt(5)
        val frameBytes = if (packed) 23 else 30
        val payload = ByteArray(5 * frameBytes) { it.toByte() }
        val stamps = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until 5) stamps.putLong(1000L + index * 20)
        return (header.array() + payload + stamps.array()).also(file::writeBytes)
    }
}
