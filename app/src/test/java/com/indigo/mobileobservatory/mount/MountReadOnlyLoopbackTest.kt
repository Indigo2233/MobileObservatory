package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** Actual TCP controller packets against a local fake endpoint. Never contacts mount hardware. */
class MountReadOnlyLoopbackTest {
    @Test fun readOnlyConnectReadsCoordinatesAndTimeButCannotTransmitMotionEvenThroughTheWireLayer() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val sent = CopyOnWriteArrayList<String>()
        var client: Socket? = null
        val worker = Thread {
            try {
                client = server.accept()
                val input = client!!.getInputStream(); val output = client!!.getOutputStream()
                val command = StringBuilder()
                while (true) {
                    val next = input.read(); if (next < 0) break
                    command.append(next.toChar())
                    if (next.toChar() != '#') continue
                    val text = command.toString(); command.setLength(0); sent += text
                    val reply = when (text) {
                        ":GR#" -> "05:00:00#"; ":GD#" -> "+40*00:00#"; ":GVP#" -> "OnStep local test#"
                        ":GC#" -> "10/14/26#"; ":GL#" -> "00:00:00#"; ":GG#" -> "+00#"; ":Td#" -> "1"
                        ":GU#" -> "NpEW160#"; ":GT#" -> "60.16427#"
                        else -> ""
                    }
                    if (reply.isNotEmpty()) { output.write(reply.toByteArray()); output.flush() }
                }
            } catch (_: java.io.IOException) {} finally { runCatching { client?.close() } }
        }.apply { isDaemon = true; start() }
        val controller = Lx200MountController { 100 }
        try {
            controller.setAccessMode(MountAccessMode.READ_ONLY)
            assertEquals(5.0, controller.connectTcp("127.0.0.1", server.localPort, protocol = MountProtocolType.LX200_ONSTEP).raHours, 1e-12)
            assertTrue(controller.pointingCapabilities.value.readCoordinates && controller.pointingCapabilities.value.readTime)
            assertFalse(controller.pointingCapabilities.value.goto || controller.supportsSync)
            assertTrue(controller.readTime().epochMillis > 0)
            assertEquals(MountTrackingState(true, MountTrackingRate.SIDEREAL), controller.readTracking())
            assertThrows(IllegalStateException::class.java) { runBlocking { controller.slewTo(MountCoordinates(6.0, 35.0)) } }
            // A future caller bypassing the public facade still cannot send a movement packet.
            val wire = Lx200MountController::class.java.getDeclaredMethod("sendNoReplyCommand", String::class.java).apply { isAccessible = true }
            val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { wire.invoke(controller, ":Mn#") }
            assertTrue(failure.cause is IllegalStateException)
            controller.stopMove(); controller.stopTrackingForSafety()
            val deadline = System.nanoTime() + 1_000_000_000L
            while ((!sent.contains(":Q#") || !sent.contains(":Td#")) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(sent.contains(":Q#") && sent.contains(":Td#"))
            assertFalse(sent.any { it.startsWith(":Sr") || it.startsWith(":MS") || it == ":Mn#" })
        } finally {
            controller.disconnect(); server.close(); client?.close(); worker.join(1000)
        }
    }
}
