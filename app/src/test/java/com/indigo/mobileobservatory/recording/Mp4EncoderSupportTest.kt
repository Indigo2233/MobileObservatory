package com.indigo.mobileobservatory.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mp4EncoderSupportTest {

    @Test
    fun `aligns dimensions to 16 pixels`() {
        assertEquals(16, Mp4EncoderSupport.align16(1))
        assertEquals(1280, Mp4EncoderSupport.align16(1280))
        assertEquals(1936, Mp4EncoderSupport.align16(1936))
        assertEquals(3008, Mp4EncoderSupport.align16(3008))
        assertEquals(3024, Mp4EncoderSupport.align16(3009))
    }

    @Test
    fun `caps bitrate for large sensors`() {
        val rate = Mp4EncoderSupport.bitrate(3008, 3008, 30)
        assertEquals(Mp4EncoderSupport.MAX_BITRATE, rate)
    }

    @Test
    fun `shrinks until the encoder accepts the size`() {
        val accepted = setOf(1280 to 1280, 960 to 960)
        val size = Mp4EncoderSupport.fitSize(3008, 3008) { w, h -> (w to h) in accepted }
        assertEquals(1280 to 1280, size)
    }

    @Test
    fun `returns null when even the minimum size is rejected`() {
        assertNull(Mp4EncoderSupport.fitSize(640, 480) { _, _ -> false })
    }
}
