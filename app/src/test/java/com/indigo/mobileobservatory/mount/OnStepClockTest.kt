package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

class OnStepClockTest {
    @Test fun writesUtcOffsetDateAndLocalTimeUsingSingleByteReplies() = runBlocking {
        inZone("Asia/Shanghai") {
            Endpoint().use { endpoint ->
                val controller = endpoint.connect()
                try {
                    val epoch = Instant.parse("2026-10-26T04:00:00Z").toEpochMilli()
                    controller.setTime(MountTime(epoch))
                    assertEquals(listOf(":SG-08#", ":SC10/26/26#", ":SL12:00:00#"), endpoint.settings())
                    assertEquals(epoch, controller.readTime().epochMillis)
                } finally { controller.disconnect() }
            }
        }
    }

    @Test fun readsTheMountsUtcOffsetIndependentlyOfThePhoneTimeZone() = runBlocking {
        inZone("Asia/Shanghai") {
            Endpoint(offset = "+00", time = "04:00:00").use { endpoint ->
                val controller = endpoint.connect()
                try {
                    assertEquals(Instant.parse("2026-10-26T04:00:00Z").toEpochMilli(), controller.readTime().epochMillis)
                    assertTrue(endpoint.commands.contains(":GG#"))
                } finally { controller.disconnect() }
            }
        }
    }

    @Test fun writesFractionalAndDaylightSavingOffsets() = runBlocking {
        for ((zone, expected) in listOf("Asia/Kathmandu" to ":SG-05:45#", "Asia/Kolkata" to ":SG-05:30#",
            "America/New_York" to ":SG+04#")) {
            inZone(zone) {
                Endpoint().use { endpoint ->
                    val controller = endpoint.connect()
                    try {
                        controller.setTime(MountTime(Instant.parse("2026-07-15T12:00:00Z").toEpochMilli()))
                        assertEquals(expected, endpoint.settings().first())
                    } finally { controller.disconnect() }
                }
            }
        }
    }

    @Test fun rejectedOffsetStopsTheTimeWrite() = runBlocking {
        inZone("Asia/Shanghai") {
            Endpoint(rejectOffset = true).use { endpoint ->
                val controller = endpoint.connect()
                try {
                    val failure = runCatching { controller.setTime(MountTime(Instant.parse("2026-10-26T04:00:00Z").toEpochMilli())) }.exceptionOrNull()
                    assertTrue(failure?.message.orEmpty(), failure?.message?.contains("UTC offset") == true)
                    assertEquals(listOf(":SG-08#"), endpoint.settings())
                } finally { controller.disconnect() }
            }
        }
    }

    @Test fun acknowledgementsCannotHideAStaleClockDuringInitialization() = runBlocking {
        inZone("Asia/Shanghai") {
            Endpoint(ignoreWrites = true).use { endpoint ->
                val controller = endpoint.connect()
                try {
                    val failure = runCatching {
                        controller.initializeTimeFromPhone(Instant.parse("2026-10-26T05:00:00Z").toEpochMilli())
                    }.exceptionOrNull()
                    assertTrue(failure?.message.orEmpty(), failure?.message?.contains("clock verification failed") == true)
                } finally { controller.disconnect() }
            }
        }
    }

    @Test fun invalidMountOffsetCannotBeReplacedWithThePhoneTimeZone() = runBlocking {
        Endpoint(offset = "unknown").use { endpoint ->
            val controller = endpoint.connect()
            try {
                assertTrue(runCatching { controller.readTime() }.exceptionOrNull()?.message?.contains("UTC offset") == true)
            } finally { controller.disconnect() }
        }
    }

    private suspend fun inZone(zone: String, action: suspend () -> Unit) {
        val previous = TimeZone.getDefault()
        try { TimeZone.setDefault(TimeZone.getTimeZone(zone)); action() }
        finally { TimeZone.setDefault(previous) }
    }

    private class Endpoint(var offset: String = "-08", var date: String = "10/26/26", var time: String = "12:00:00",
        private val rejectOffset: Boolean = false, private val ignoreWrites: Boolean = false) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val commands = CopyOnWriteArrayList<String>()
        @Volatile private var client: Socket? = null
        private val worker = Thread {
            try {
                val connection = server.accept().also { client = it }
                val input = connection.getInputStream(); val output = connection.getOutputStream()
                val packet = StringBuilder()
                while (true) {
                    val next = input.read(); if (next < 0) break
                    packet.append(next.toChar()); if (next.toChar() != '#') continue
                    val command = packet.toString(); packet.setLength(0); commands += command
                    val reply = when {
                        command == ":GR#" -> "05:00:00#"
                        command == ":GD#" -> "+40*00:00#"
                        command == ":GVP#" -> "On-Step#"
                        command == ":GG#" -> "$offset#"
                        command == ":GC#" -> "$date#"
                        command == ":GL#" -> "$time#"
                        command.startsWith(":SG") -> if (rejectOffset) "0" else { if (!ignoreWrites) offset = command.substring(3).dropLast(1); "1" }
                        command.startsWith(":SC") -> { if (!ignoreWrites) date = command.substring(3).dropLast(1); "1" }
                        command.startsWith(":SL") -> { if (!ignoreWrites) time = command.substring(3).dropLast(1); "1" }
                        else -> ""
                    }
                    if (reply.isNotEmpty()) { output.write(reply.toByteArray()); output.flush() }
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        suspend fun connect() = Lx200MountController().also {
            it.connectTcp("127.0.0.1", server.localPort, timeoutMs = 250, protocol = MountProtocolType.LX200_ONSTEP)
        }
        fun settings() = commands.filter { it.startsWith(":S") }
        override fun close() { server.close(); client?.close(); worker.join(1000) }
    }
}
