package com.indigo.mobileobservatory.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyWatcherAdapterTest {
    @Test
    fun openRejectsMountThatHasNotBeenAligned() {
        val adapter = SkyWatcherAdapter { payload, _ ->
            when (payload.toString(Charsets.US_ASCII)) {
                "Kx" -> "x#".toByteArray()
                "J" -> byteArrayOf(0, '#'.code.toByte())
                else -> byteArrayOf()
            }
        }

        val failure = assertThrows(IllegalArgumentException::class.java) {
            adapter.open()
        }

        assertTrue(failure.message.orEmpty().contains("not aligned"))
    }

    @Test
    fun stopAllAxesSendsZeroRateForEveryDirection() {
        val payloads = mutableListOf<ByteArray>()
        val adapter = SkyWatcherAdapter { payload, _ ->
            payloads += payload.copyOf()
            ByteArray(8)
        }

        adapter.stopMove(null)

        assertEquals(4, payloads.size)
        assertEquals(setOf(16, 17), payloads.map { it[2].toInt() }.toSet())
        assertEquals(setOf(36, 37), payloads.map { it[3].toInt() }.toSet())
        assertTrue(payloads.all { it[4].toInt() == 0 })
    }
}
