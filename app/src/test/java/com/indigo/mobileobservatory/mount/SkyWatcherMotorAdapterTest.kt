package com.indigo.mobileobservatory.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SkyWatcherMotorAdapterTest {
    @Test
    fun openReadsGeometryAndCoordinatesFromMotorReplies() {
        val queue = motorReplies()
        val sent = mutableListOf<String>()
        val adapter = SkyWatcherMotorAdapter(
            exchange = { payload ->
                sent += payload.toString(Charsets.US_ASCII)
                queue.removeFirst().toByteArray()
            },
            site = MountSite(31.0, 121.0),
            now = { Instant.parse("2026-01-01T00:00:00Z") }
        )
        val coordinates = adapter.open()
        assertTrue(adapter.supportsSync)
        assertTrue(sent.first().startsWith(":e1"))
        assertEquals(90.0, coordinates.decDeg, 1e-4)
    }

    @Test
    fun syncWritesEncoderTargets() {
        val sent = mutableListOf<String>()
        val adapter = openedAdapter(sent)
        adapter.syncTo(MountCoordinates(5.0, 40.0))
        assertTrue(sent.any { it.startsWith(":E1") })
        assertTrue(sent.any { it.startsWith(":E2") })
    }

    private fun openedAdapter(sent: MutableList<String> = mutableListOf()): SkyWatcherMotorAdapter {
        val queue = motorReplies()
        val idle = "=101\r"
        return SkyWatcherMotorAdapter(
            exchange = { payload ->
                val command = payload.toString(Charsets.US_ASCII)
                sent += command
                when {
                    queue.isNotEmpty() -> queue.removeFirst().toByteArray()
                    command.startsWith(":j") -> positionReply(0x800000).toByteArray()
                    command.startsWith(":f") -> idle.toByteArray()
                    else -> "=\r".toByteArray()
                }
            },
            site = MountSite(31.0, 121.0),
            now = { Instant.parse("2026-01-01T00:00:00Z") }
        ).also { it.open() }
    }

    private fun motorReplies(): ArrayDeque<String> {
        val cpr = SkyWatcherMotorCodec.intToHex(12_960_000, 6)
        val timer = SkyWatcherMotorCodec.intToHex(16_000_000, 6)
        val ratio = SkyWatcherMotorCodec.intToHex(16, 6)
        return ArrayDeque(
            listOf(
                "=${SkyWatcherMotorCodec.intToHex(0x040000, 6)}\r",
                "=$cpr\r",
                "=$cpr\r",
                "=$timer\r",
                "=$ratio\r",
                "=$timer\r",
                "=$ratio\r",
                "=101\r",
                "=101\r",
                positionReply(0x800000),
                positionReply(0x800000 + 12_960_000 / 4)
            )
        )
    }

    private fun positionReply(steps: Long): String =
        "=${SkyWatcherMotorCodec.intToHex(steps, 6)}\r"
}
