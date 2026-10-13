package com.indigo.mobileobservatory.recording

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.stacking.SampleAlignment
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assert.*
import org.junit.Test

class SERPrecisionReaderTest {
    private fun header(w: Int, h: Int, bits: Int, count: Int, color: Int = 0, endian: Int = 0): ByteArray {
        val b = ByteBuffer.allocate(178).order(ByteOrder.LITTLE_ENDIAN)
        b.put("LUCAM-RECORDER".toByteArray(Charsets.US_ASCII))
        b.position(18); b.putInt(color); b.putInt(endian); b.putInt(w); b.putInt(h); b.putInt(bits); b.putInt(count)
        return b.array()
    }

    @Test fun externalRgbAndBgrBothByteOrdersAndSpecOverridePreservePrecision() {
        val file = Files.createTempFile("external-ser", ".ser").toFile()
        try {
            for (color in listOf(100, 101)) for (endian in 0..1) for (bits in listOf(8, 10, 12, 14, 16)) {
                val values = intArrayOf(71, 73, 75, 151, 153, 155)
                val bpp = if (bits <= 8) 1 else 2
                val data = ByteBuffer.allocate(values.size * bpp).order(if (endian == 0) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                for (v in values) if (bpp == 1) data.put(v.toByte()) else data.putShort(v.toShort())
                file.writeBytes(header(2, 1, bits, 1, color, endian) + data.array())
                SERReader(file).use { r ->
                    assertTrue(r.lastError, r.open())
                    assertEquals((values.size * bpp).toLong(), r.frameByteSize)
                    val image = r.readLinearFrame(0)!!
                    val max = (1 shl bits) - 1
                    for (i in values.indices) {
                        val expected = values[if (color == 101) i / 3 * 3 + 2 - i % 3 else i] / max.toFloat()
                        assertEquals(expected, image.pixels[i], 1e-7f)
                    }
                }
                file.writeBytes(header(2, 1, bits, 1, color, 1 - endian) + data.array())
                SERReader(file, SerEndianConvention.SPECIFICATION).use { r ->
                    assertTrue(r.open()); assertEquals(73f / ((1 shl bits) - 1), r.readLinearFrame(0)!!.pixels[1], 1e-7f)
                }
            }
        } finally { file.delete() }
    }

    @Test fun writerMultipleFramesDoNotOverwriteHeaderAndPserTailPixelsRoundTrip() {
        for (bits in listOf(8, 10, 12, 14, 16)) for (packed in listOf(false, true)) {
            val file = Files.createTempFile("writer-precision", if (packed) ".pser" else ".ser").toFile()
            try {
                val format = PixelFormat.valueOf("MONO$bits")
                val max = (1 shl bits) - 1
                val values = intArrayOf(1, max / 2, max / 2 + 1, max - 1, max)
                fun frame(id: Long): FrameData {
                    val b = ByteBuffer.allocate(5 * format.bytesPerPixel).order(ByteOrder.LITTLE_ENDIAN)
                    for (v in values) if (bits == 8) b.put((v - id + 1).toByte()) else b.putShort((v - id + 1).toShort())
                    return FrameData(b.array(), 5, 1, format, id, 0)
                }
                if (packed) {
                    val writer = PSERWriter(file); writer.open(5, 1, format, "Camera proof")
                    writer.writeFrame(frame(1)); writer.writeFrame(frame(2)); writer.close()
                } else {
                    val writer = SERWriter(file); writer.open(5, 1, format, "Camera proof")
                    writer.writeFrame(frame(1)); writer.writeFrame(frame(2)); writer.close()
                }
                // Independent checks of physical layout, not just a writer/reader symmetry test.
                val all = file.readBytes()
                assertEquals("MobileObservatory", String(all, 42, 17, Charsets.US_ASCII))
                assertEquals("Camera proof", String(all, 82, 12, Charsets.US_ASCII))
                val frameBytes = if (packed) (5 * bits + 7) / 8 else 5 * format.bytesPerPixel
                assertEquals(178 + 2 * frameBytes + 16, all.size)
                assertEquals(1, all[178].toInt() and 255)
                assertEquals(0, all[178 + frameBytes].toInt() and 255)
                SERReader(file).use { reader ->
                    assertTrue(reader.open()); assertEquals(2, reader.header!!.frameCount)
                    for (index in 0..1) {
                        val image = reader.readLinearFrame(index)!!
                        for (i in values.indices) assertEquals((values[i] - index).toFloat() / max, image.pixels[i], 1e-7f)
                        val display = reader.decodeFrameTo8bit(reader.readFrame(index)!!)
                        assertEquals(((values.last() - index) * 255L / max).toInt(), display.last().toInt() and 255)
                    }
                    assertNotNull(reader.readTimestamp(1))
                }
            } finally { file.delete() }
        }
    }

    @Test fun malformedAndTruncatedInputNeverOpensAndCanThenReopenValidFile() {
        val file = Files.createTempFile("bad-ser", ".ser").toFile()
        try {
            val invalid = listOf(ByteArray(20), ByteArray(178), header(-1, 2, 8, 1), header(2, 2, 17, 1),
                header(2, 2, 8, -1), header(2, 2, 8, 0), header(2, 2, 8, 1, color = 77),
                header(2, 2, 8, 1) + ByteArray(3), header(2, 2, 8, 1) + ByteArray(7),
                header(Int.MAX_VALUE, Int.MAX_VALUE, 16, Int.MAX_VALUE))
            val reader = SERReader(file)
            for (bytes in invalid) {
                file.writeBytes(bytes); assertFalse(reader.open()); assertNotNull(reader.lastError); assertNull(reader.header)
            }
            file.writeBytes(header(2, 2, 8, 1) + ByteArray(4))
            assertTrue(reader.open()); reader.close(); assertNull(reader.readFrame(0))
        } finally { file.delete() }
    }

    @Test fun sparseFrameOffsetBeyondTwoGiBUsesLongAndReadsOnlyOneFrame() {
        val directory = Files.createTempDirectory("large-ser")
        val path = directory.resolve("sparse.ser")
        try {
            val frameSize = 1024 * 1024
            val count = 2050
            val lastOffset = 178L + frameSize.toLong() * (count - 1)
            Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).use { channel ->
                channel.write(ByteBuffer.wrap(header(1024, 1024, 8, count)))
                channel.position(lastOffset); channel.write(ByteBuffer.wrap(byteArrayOf(117)))
                channel.position(178L + frameSize.toLong() * count - 1L); channel.write(ByteBuffer.wrap(byteArrayOf(219.toByte())))
            }
            SERReader(path.toFile()).use { reader ->
                assertTrue(reader.lastError, reader.open())
                assertTrue(reader.frameOffset(count - 1) > Int.MAX_VALUE)
                assertEquals(lastOffset, reader.frameOffset(count - 1))
                val bytes = reader.readFrame(count - 1)!!
                assertEquals(117, bytes.first().toInt() and 255)
                assertEquals(219, bytes.last().toInt() and 255)
                assertNull(reader.frameTimestamps)
            }
        } finally { Files.deleteIfExists(path); Files.delete(directory) }
    }

    @Test fun explicitLeftAlignedSerDoesNotLoseLowSignal() {
        val file = Files.createTempFile("left-ser", ".ser").toFile()
        try {
            file.writeBytes(header(2, 1, 12, 1) + byteArrayOf(16, 0, 32, 0))
            SERReader(file).use { reader ->
                reader.sampleAlignment = SampleAlignment.LEFT
                assertTrue(reader.open())
                assertEquals(1f / 4095, reader.readLinearFrame(0)!!.pixels[0], 1e-8f)
            }
        } finally { file.delete() }
    }
}
