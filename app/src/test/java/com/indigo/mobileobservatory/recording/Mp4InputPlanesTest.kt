package com.indigo.mobileobservatory.recording

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Mp4InputPlanesTest {
    @Test fun interleavedPlanesRespectUvAndVuOffsetsAndPaddedFinalRows() {
        val uv = byteArrayOf(11, 21, 12, 22, 13, 23, 14, 24)
        for (vu in listOf(false, true)) {
            val bytes = ByteArray(10) { 99 }
            fun plane(offset: Int) = ByteBuffer.wrap(bytes).apply { position(offset) }.slice()
            val u = if (vu) 1 else 0
            val v = 1 - u
            Mp4InputPlanes.copy(uv, 0, 4, 2, plane(u), 6, 2, 2, 2)
            Mp4InputPlanes.copy(uv, 1, 4, 2, plane(v), 6, 2, 2, 2)
            assertEquals(11.toByte(), bytes[u]); assertEquals(21.toByte(), bytes[v])
            assertEquals(14.toByte(), bytes[8 + u]); assertEquals(24.toByte(), bytes[8 + v])
            assertEquals(99.toByte(), bytes[4]); assertEquals(99.toByte(), bytes[5])
        }
    }

    @Test fun planarCopyHonorsNonzeroPositionWithoutChangingBufferCursorOrPadding() {
        val bytes = ByteArray(12) { 99 }
        val plane = ByteBuffer.wrap(bytes).apply { position(2); limit(10) }
        Mp4InputPlanes.copy(byteArrayOf(1, 2, 3, 4, 5, 6), 0, 3, 1, plane, 5, 1, 3, 2)
        assertArrayEquals(byteArrayOf(99, 99, 1, 2, 3, 99, 99, 4, 5, 6, 99, 99), bytes)
        assertEquals(2, plane.position()); assertEquals(10, plane.limit())
    }

    @Test fun truncatedPlaneFailsBeforeWritingInsteadOfSilentlyEncodingCorruptData() {
        val bytes = ByteArray(7) { 99 }
        assertThrows(IllegalArgumentException::class.java) {
            Mp4InputPlanes.copy(ByteArray(6), 0, 3, 1, ByteBuffer.wrap(bytes), 5, 1, 3, 2)
        }
        assertTrue(bytes.all { it == 99.toByte() })
    }
}
