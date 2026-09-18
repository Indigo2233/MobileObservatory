package com.indigo.mobileobservatory.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyWatcherMotorCodecTest {
    @Test
    fun intToHexSwapsBytePairs() {
        assertEquals("563412", SkyWatcherMotorCodec.intToHex(0x123456, 6))
        assertEquals("3412", SkyWatcherMotorCodec.intToHex(0x1234, 4))
        assertEquals("12", SkyWatcherMotorCodec.intToHex(0x12, 2))
    }

    @Test
    fun hexToIntReversesBytePairs() {
        assertEquals(0x123456L, SkyWatcherMotorCodec.hexToInt("563412"))
        assertEquals(0x1234L, SkyWatcherMotorCodec.hexToInt("3412"))
        assertEquals(0x101L, SkyWatcherMotorCodec.hexToInt("101"))
    }

    @Test
    fun commandFramesAxisAndTerminator() {
        val encoded = SkyWatcherMotorCodec.command('e', SkyWatcherAxis.RA)
        assertEquals(":e1\r", encoded.toString(Charsets.US_ASCII))
        val withData = SkyWatcherMotorCodec.command('E', SkyWatcherAxis.DEC, 0x800000)
        assertEquals(":E2000080\r", withData.toString(Charsets.US_ASCII))
    }

    @Test
    fun parseReplyAcceptsEqualsAndRejectsErrors() {
        assertEquals("101", SkyWatcherMotorCodec.parseReply("=101\r".toByteArray()))
        val failure = runCatching {
            SkyWatcherMotorCodec.parseReply("!2\r".toByteArray())
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Motor not stopped"))
    }

    @Test
    fun motorReplyDetection() {
        assertTrue(SkyWatcherMotorCodec.isMotorReply("=\r".toByteArray()))
        assertTrue(SkyWatcherMotorCodec.isMotorReply("!0\r".toByteArray()))
        assertFalse(SkyWatcherMotorCodec.isMotorReply("x#".toByteArray()))
    }
}
