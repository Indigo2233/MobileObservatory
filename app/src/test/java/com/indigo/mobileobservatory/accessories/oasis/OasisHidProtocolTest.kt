package com.indigo.mobileobservatory.accessories.oasis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OasisHidProtocolTest {
    @Test
    fun encodes65ByteHidReport() {
        val report = OasisHidProtocol.encode(0x36, OasisHidProtocol.int32(0x12345678))

        assertEquals(65, report.size)
        assertEquals(0, report[0].toInt())
        assertEquals(0x36, report[1].toInt() and 0xFF)
        assertEquals(4, report[2].toInt())
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), report.copyOfRange(3, 7))
    }

    @Test
    fun stripsReportIdFor64ByteUsbEndpoint() {
        val hidApiReport = OasisHidProtocol.encode(0x3C)

        val endpointReport = OasisHidProtocol.endpointReport(hidApiReport, 64)

        assertEquals(64, endpointReport.size)
        assertEquals(0x3C, endpointReport[0].toInt() and 0xFF)
        assertEquals(0, endpointReport[1].toInt() and 0xFF)
    }

    @Test
    fun decodesHidResponseWithReportId() {
        val message = OasisHidProtocol.decode(OasisHidProtocol.encode(0x50, byteArrayOf(7)))

        assertNotNull(message)
        assertEquals(0x50, message?.command)
        assertArrayEquals(byteArrayOf(7), message?.payload)
    }

    @Test
    fun parsesFocuserStatusAcrossGenerations() {
        val payload = ByteArray(40)
        OasisHidProtocol.int32(2_500).copyInto(payload, 4)
        payload[8] = 1
        OasisHidProtocol.int32(123_456).copyInto(payload, 10)

        val status = OasisFocuserProtocol.parseStatus(payload)

        assertNotNull(status)
        assertEquals(123_456, status?.position)
        assertTrue(status?.moving == true)
        assertEquals(25f, status?.temperatureC)
    }

    @Test
    fun parsesFilterWheelStateAndOneBasedPosition() {
        val payload = ByteArray(38)
        OasisHidProtocol.int32(2_000).copyInto(payload, 0)
        payload[4] = OasisFilterWheelProtocol.statusMoving.toByte()
        payload[5] = 7

        val status = OasisFilterWheelProtocol.parseStatus(payload)

        assertNotNull(status)
        assertEquals(OasisFilterWheelProtocol.statusMoving, status?.state)
        assertEquals(7, status?.position)
        assertNotNull(status?.temperatureC)
        assertFalse(status?.state == OasisFilterWheelProtocol.statusIdle)
    }

    @Test
    fun encodesFilterWheelSlotNameQuery() {
        val payload = OasisFilterWheelProtocol.slotNameQueryPayload(7)

        assertEquals(17, payload.size)
        assertEquals(7, payload[0].toInt() and 0xFF)
        assertTrue(payload.copyOfRange(1, payload.size).all { it == 0.toByte() })
    }
}
