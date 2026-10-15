package com.indigo.mobileobservatory.accessories.zwo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZwoEafProtocolTest {
    private val capturedStatus = byteArrayOf(
        1, 126, 90, 3, 0, 0, 0, 0, 155.toByte(), 149.toByte(), 0,
        128.toByte(), 82, 1, 234.toByte(), 96
    )

    @Test
    fun encodesStatusQueryWithOutputReportId() {
        assertArrayEquals(
            byteArrayOf(3, 126, 90, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            ZwoEafProtocol.query(ZwoEafProtocol.selectorStatus)
        )
    }

    @Test
    fun parsesCapturedStatusReport() {
        val status = ZwoEafProtocol.parseStatus(capturedStatus)

        assertNotNull(status)
        assertEquals(39_829, status?.position)
        assertEquals(60_000, status?.maxStep)
        assertEquals(28.5f, status?.temperatureC)
        assertTrue(status?.beep == true)
        assertFalse(status?.reverse == true)
        assertFalse(status?.moving == true)
        assertEquals(0, status?.errorCode)
    }

    @Test
    fun rejectsMalformedAndOutOfRangeStatus() {
        assertNull(ZwoEafProtocol.parseStatus(ByteArray(15)))
        assertNull(ZwoEafProtocol.parseStatus(capturedStatus.copyOf().also { it[3] = 4 }))
        assertNull(ZwoEafProtocol.parseStatus(capturedStatus.copyOf().also {
            it[6] = 10
            it[14] = 0
            it[15] = 0
        }))
    }

    @Test
    fun motionAndStopPreserveDeviceSettings() {
        val status = requireNotNull(ZwoEafProtocol.parseStatus(capturedStatus))

        assertArrayEquals(
            byteArrayOf(
                3, 126, 90, 3, 1, 0, 0, 0, 155.toByte(), 199.toByte(), 0,
                0, 0, 1, 234.toByte(), 96
            ),
            ZwoEafProtocol.moveCommand(status, 39_879)
        )
        assertEquals(0, ZwoEafProtocol.stopCommand(status)[4].toInt() and 0xFF)
    }

    @Test
    fun encodesResetAndSettingsUpdateSelectors() {
        val status = requireNotNull(ZwoEafProtocol.parseStatus(capturedStatus))
        val reset = ZwoEafProtocol.resetPositionCommand(status, 0)
        val settings = ZwoEafProtocol.settingsCommand(
            status,
            reverse = true,
            backlash = 7,
            maxStep = 100_000
        )

        assertEquals(1, reset[10].toInt() and 0xFF)
        assertEquals(0, reset[7].toInt() and 0xFF)
        assertEquals(0, reset[8].toInt() and 0xFF)
        assertEquals(0, reset[9].toInt() and 0xFF)
        assertEquals(2, settings[10].toInt() and 0xFF)
        assertEquals(7, settings[5].toInt() and 0xFF)
        assertEquals(3, settings[13].toInt() and 0xFF)
        assertEquals(100_000 ushr 16, settings[6].toInt() and 0xFF)
        assertEquals(100_000 and 0xFFFF, bytesToU16(settings[14], settings[15]))
    }

    @Test
    fun parsesVerifiedIdentityAndSerial() {
        val identityReport = byteArrayOf(
            1, 126, 90, 4, 3, 3, 6, 0,
            'E'.code.toByte(), 'A'.code.toByte(), 'F'.code.toByte(), 'N'.code.toByte(),
            0, 0, 0, 0
        )
        val serialReport = byteArrayOf(
            1, 126, 90, 12, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 0
        )

        val identity = ZwoEafProtocol.parseIdentity(identityReport, serialReport)

        assertNotNull(identity)
        assertEquals("EAFN", identity?.model)
        assertEquals("3.3.6", identity?.firmware)
        assertEquals("0102030405060708", identity?.serial)
        assertTrue(ZwoEafProtocol.supportsVerifiedProtocol(requireNotNull(identity)))
    }

    private fun bytesToU16(high: Byte, low: Byte): Int =
        ((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)
}
