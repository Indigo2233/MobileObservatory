package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class MountTrackingLoopbackTest {
    private class Endpoint(val reply: (String) -> String) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val commands = CopyOnWriteArrayList<String>()
        @Volatile private var client: Socket? = null
        private val worker = Thread {
            runCatching {
                val socket = server.accept().also { client = it }
                val buffer = StringBuilder()
                while (!socket.isClosed) {
                    val value = socket.getInputStream().read()
                    if (value < 0) break
                    buffer.append(value.toChar())
                    if (value.toChar() != '#') continue
                    val command = buffer.toString()
                    buffer.clear()
                    commands += command
                    socket.getOutputStream().apply { write(reply(command).toByteArray()); flush() }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { runCatching { client?.close() }; server.close(); worker.join(1000) }
    }

    @Test fun ioptronStatusAndCoordinatesConsumeTheirTerminatorsAcrossRepeatedReads() = runBlocking {
        var status = '1'
        Endpoint { command -> when (command) {
            ":MountInfo#" -> "0026"
            ":GLS#" -> "+00000000324000002${status}0911#"
            ":GEP#" -> "+0720000005400000001#"
            else -> error("Unexpected command $command")
        } }.use { endpoint ->
            val controller = Lx200MountController { 100 }
            try {
                controller.setAccessMode(MountAccessMode.READ_ONLY)
                controller.connectTcp("127.0.0.1", endpoint.port, protocol = MountProtocolType.IOPTRON)
                repeat(3) {
                    assertEquals(MountTrackingState(true, MountTrackingRate.SIDEREAL), controller.readTracking())
                    assertEquals(10.0, controller.readCoordinates().raHours, 1e-8)
                    assertEquals(0.0, controller.readSite().latitudeDeg, 1e-8)
                }
                status = '7'
                assertEquals(MountTrackingState(false, MountTrackingRate.OFF), controller.readTracking())
            } finally { controller.disconnect() }
        }
    }

    @Test fun genericLx200ReportsUnknownWithoutGuessingFromTheSelectedFrequency() = runBlocking {
        Endpoint { command -> when (command) {
            ":GVP#" -> "Classic LX200#"
            ":GR#" -> "10:00:00#"
            ":GD#" -> "+20*00:00#"
            else -> error("Unexpected command $command")
        } }.use { endpoint ->
            val controller = Lx200MountController { 100 }
            try {
                controller.connectTcp("127.0.0.1", endpoint.port, protocol = MountProtocolType.LX200_ONSTEP)
                assertFalse(controller.supportsTrackingReadback)
                assertEquals(MountTrackingState(), controller.readTracking())
                assertFalse(endpoint.commands.any { it == ":GT#" || it == ":GU#" })
            } finally { controller.disconnect() }
        }
    }
}
