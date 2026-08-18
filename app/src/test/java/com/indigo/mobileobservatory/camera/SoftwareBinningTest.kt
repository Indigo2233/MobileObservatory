package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareBinningTest {
    @Test
    fun averagesMono8Blocks() {
        val src = byteArrayOf(
            0, 10, 20, 30,
            40, 50, 60, 70,
            1, 1, 2, 2,
            3, 3, 4, 4
        )
        val dst = ByteArray(4)
        SoftwareBinning.binMono8(src, 4, dst, 2, 2, 2)
        assertEquals(25, dst[0].toInt() and 0xFF)
        assertEquals(45, dst[1].toInt() and 0xFF)
        assertEquals(2, dst[2].toInt() and 0xFF)
        assertEquals(3, dst[3].toInt() and 0xFF)
    }

    @Test
    fun averagesLittleEndianMono16() {
        val src = ByteArray(8)
        writeLe16(src, 0, 100)
        writeLe16(src, 2, 200)
        writeLe16(src, 4, 300)
        writeLe16(src, 6, 400)
        val dst = ByteArray(2)
        SoftwareBinning.binMono16(src, 2, dst, 1, 1, 2)
        assertEquals(250, (dst[0].toInt() and 0xFF) or ((dst[1].toInt() and 0xFF) shl 8))
    }

    @Test
    fun applyReturnsOriginalWhenBinIs1() {
        val binning = SoftwareBinning()
        val frame = FrameData(ByteArray(4), 2, 2, PixelFormat.MONO8, 1, 1)
        assertSame(frame, binning.apply(frame))
    }

    @Test
    fun applyOwnsAndReleasesBinnedBuffers() {
        val binning = SoftwareBinning()
        binning.factor = 2
        val src = ByteArray(16)
        val frame = FrameData(src, 4, 4, PixelFormat.MONO8, 1, 1)
        val binned = binning.apply(frame)
        assertNotSame(frame, binned)
        assertEquals(2, binned.width)
        assertEquals(2, binned.height)
        assertTrue(binning.release(binned.data))
    }

    @Test
    fun averagesRgb24Channels() {
        val src = byteArrayOf(
            10, 0, 0, 20, 0, 0,
            30, 0, 0, 40, 0, 0
        )
        val dst = ByteArray(3)
        SoftwareBinning.binRgb24(src, 2, dst, 1, 1, 2)
        assertArrayEquals(byteArrayOf(25, 0, 0), dst)
    }

    private fun writeLe16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
