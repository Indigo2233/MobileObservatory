package com.indigo.mobileobservatory.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MountProtocolCodecTest {
    @Test
    fun parsesLx200CoordinatesWithCommonDegreeSeparators() {
        val coordinates = MountProtocolCodec.parseLx200Coordinates(
            raResponse = "23:59:30#",
            decResponse = "-12\u00b034:56#"
        )

        assertEquals(23.991666, coordinates.raHours, 0.000001)
        assertEquals(-12.582222, coordinates.decDeg, 0.000001)
    }

    @Test
    fun encodesLx200GotoWithWrappedRaAndSignedDeclination() {
        val commands = MountProtocolCodec.encodeLx200Goto(
            MountCoordinates(raHours = 25.5, decDeg = -8.25)
        )

        assertEquals(":Sr01:30:00#", commands.ra)
        assertEquals(":Sd-08*15:00#", commands.dec)
    }

    @Test
    fun parsesIoptronFixedWidthCoordinates() {
        val coordinates = MountProtocolCodec.parseIoptronCoordinates(
            "+03240000" + "097200000" + "00"
        )

        assertEquals(18.0, coordinates.raHours, 0.000001)
        assertEquals(9.0, coordinates.decDeg, 0.000001)
    }

    @Test
    fun rejectsMalformedCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            MountProtocolCodec.parseLx200Coordinates("not-ra", "+10*00:00")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MountProtocolCodec.parseIoptronCoordinates("short")
        }
    }
}
