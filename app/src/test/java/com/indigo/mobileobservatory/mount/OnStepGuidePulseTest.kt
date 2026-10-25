package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** Tests the shared USB/TCP protocol encoder against a local endpoint; no hardware motion. */
class OnStepGuidePulseTest {
    @Test fun onStepUsesAcknowledgedControllerTimedPulsesAndConsumesEveryReply(): Unit = runBlocking {
        Endpoint().use { endpoint ->
            val controller = Lx200MountController { 100 }
            try {
                controller.connectTcp("127.0.0.1", endpoint.port, protocol = MountProtocolType.LX200_ONSTEP)
                controller.setMoveRate(MountSlewRate.GUIDE)
                val directions = MountDirection.values()
                for ((index, direction) in directions.withIndex()) {
                    controller.startGuidePulse(direction, 1200 + index)
                    controller.stopGuidePulse(direction)
                }
                assertEquals(5.0, controller.readCoordinates().raHours, 0.0)
                assertTrue(endpoint.commands.containsAll(listOf(":R2#", ":MGn1200#", ":MGs1201#", ":MGe1202#", ":MGw1203#",
                    ":Qn#", ":Qs#", ":Qe#", ":Qw#", ":Q#")))
                assertFalse(endpoint.commands.any { it in listOf(":Mn#", ":Ms#", ":Me#", ":Mw#") })
            } finally { controller.disconnect() }
        }
    }

    @Test fun rejectedTimedPulseDoesNotFallBackToContinuousMotionOrLeaveAReply(): Unit = runBlocking {
        Endpoint(acceptPulse = false).use { endpoint ->
            val controller = Lx200MountController { 100 }
            try {
                controller.connectTcp("127.0.0.1", endpoint.port, protocol = MountProtocolType.LX200_ONSTEP)
                assertThrows(IllegalStateException::class.java) { runBlocking { controller.startGuidePulse(MountDirection.EAST, 2000) } }
                assertEquals(5.0, controller.readCoordinates().raHours, 0.0)
                assertTrue(endpoint.commands.contains(":MGe2000#"))
                assertFalse(endpoint.commands.contains(":Me#"))
            } finally { controller.disconnect() }
        }
    }

    @Test fun limitsReadOnlyAndUnavailableStopAreReported(): Unit = runBlocking {
        Endpoint().use { endpoint ->
            val controller = Lx200MountController { 100 }
            try {
                controller.connectTcp("127.0.0.1", endpoint.port, protocol = MountProtocolType.LX200_ONSTEP)
                for (duration in listOf(0, 5001)) assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { controller.startGuidePulse(MountDirection.NORTH, duration) }
                }
                controller.setAccessMode(MountAccessMode.READ_ONLY)
                assertThrows(IllegalStateException::class.java) { runBlocking { controller.startGuidePulse(MountDirection.NORTH, 100) } }
                controller.stopGuidePulse(MountDirection.NORTH)
                controller.readCoordinates()
                assertFalse(endpoint.commands.any { it.startsWith(":MG") })
            } finally { controller.disconnect() }
            assertThrows(IllegalStateException::class.java) { runBlocking { controller.stopGuidePulse(MountDirection.NORTH) } }
        }
    }

    @Test fun otherLx200ControllersKeepTheirExistingMotionCommands(): Unit = runBlocking {
        Endpoint(model = "LX200 test").use { endpoint ->
            val controller = Lx200MountController { 100 }
            try {
                controller.connectTcp("127.0.0.1", endpoint.port, protocol = MountProtocolType.LX200_ONSTEP)
                controller.startGuidePulse(MountDirection.EAST, 500)
                controller.stopGuidePulse(MountDirection.EAST)
                controller.readCoordinates()
                assertTrue(endpoint.commands.contains(":Me#"))
                assertFalse(endpoint.commands.any { it.startsWith(":MG") })
            } finally { controller.disconnect() }
        }
    }

    private class Endpoint(private val model: String = "OnStep test", private val acceptPulse: Boolean = true) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val commands = CopyOnWriteArrayList<String>()
        @Volatile private var client: Socket? = null
        private val worker = Thread {
            try {
                val connection = server.accept().also { client = it }
                val input = connection.getInputStream()
                val output = connection.getOutputStream()
                val packet = StringBuilder()
                while (true) {
                    val next = input.read()
                    if (next < 0) break
                    packet.append(next.toChar())
                    if (next.toChar() != '#') continue
                    val command = packet.toString()
                    packet.setLength(0)
                    commands += command
                    val response = when {
                        command == ":GR#" -> "05:00:00#"
                        command == ":GD#" -> "+40*00:00#"
                        command == ":GVP#" -> "$model#"
                        command.startsWith(":MG") -> if (acceptPulse) "1" else "0"
                        else -> ""
                    }
                    if (response.isNotEmpty()) { output.write(response.toByteArray()); output.flush() }
                }
            } catch (_: java.io.IOException) { /* Endpoint is closed by the test. */ }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); client?.close(); worker.join(1000) }
    }
}
