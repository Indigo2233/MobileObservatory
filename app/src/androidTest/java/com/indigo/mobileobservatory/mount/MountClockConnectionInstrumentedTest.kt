package com.indigo.mobileobservatory.mount

import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the actual connection lifecycle with an isolated OnStep protocol endpoint. */
class MountClockConnectionInstrumentedTest {
    @Test fun connectionSynchronizesAndVerifiesTimeBeforeBecomingReady() = runBlocking {
        exercise { module, endpoint ->
            assertNotNull(module.mountTime.value)
            assertTrue(kotlin.math.abs(module.mountTime.value!!.epochMillis - System.currentTimeMillis()) < 10_000)
            assertEquals(listOf(":SG", ":SC", ":SL"), endpoint.commands.filter { it.startsWith(":S") }.map { it.take(3) })
            assertTrue(endpoint.commands.containsAll(listOf(":GG#", ":GC#", ":GL#")))
        }
    }

    @Test fun failedTimeWriteKeepsTheConnectionAndShowsTheFailure() = runBlocking {
        exercise(rejectOffset = true) { module, endpoint ->
            assertNull(module.mountTime.value)
            assertTrue(module.statusMessage.value, module.statusMessage.value.contains("Mount rejected UTC offset."))
            assertFalse(endpoint.commands.any { it.startsWith(":SC") || it.startsWith(":SL") })
        }
    }

    @Test fun readOnlyConnectionReadsTimeWithoutWritingIt() = runBlocking {
        exercise(readOnly = true) { module, endpoint ->
            assertNotNull(module.mountTime.value)
            assertTrue(endpoint.commands.contains(":GG#"))
            assertFalse(endpoint.commands.any { it.startsWith(":S") })
        }
    }

    private suspend fun exercise(rejectOffset: Boolean = false, readOnly: Boolean = false,
        verify: (MountModule, Endpoint) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val preferenceName = "clock-connection-${System.nanoTime()}"
        val app = object : Application() {
            init { attachBaseContext(base) }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(preferenceName, mode)
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val module = MountModule(app, owner)
        try {
            Endpoint(rejectOffset).use { endpoint ->
                module.setMountProtocol(MountProtocolType.LX200_ONSTEP)
                module.setMountTransport(MountTransportType.TCP)
                module.setMountAccessMode(if (readOnly) MountAccessMode.READ_ONLY else MountAccessMode.READ_WRITE)
                module.setMountHost("127.0.0.1"); module.setMountPort(endpoint.port.toString())
                module.connectMount()
                withTimeout(15_000) {
                    while ((module.mountConnectionState.value !is MountConnectionState.Connected &&
                        module.mountConnectionState.value !is MountConnectionState.Error) || module.mountBusy.value) delay(20)
                }
                assertEquals(module.statusMessage.value, MountConnectionState.Connected, module.mountConnectionState.value)
                assertFalse(module.mountBusy.value)
                verify(module, endpoint)
                module.closeController()
            }
        } finally {
            module.cancelJobs(); module.closeController(); owner.cancel()
            app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit().clear().commit()
            base.deleteSharedPreferences(preferenceName)
        }
    }

    private class Endpoint(private val rejectOffset: Boolean) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val commands = CopyOnWriteArrayList<String>()
        @Volatile private var client: Socket? = null
        private var offset = "+00"; private var date = "10/26/26"; private var time = "12:00:00"
        private val worker = Thread {
            try {
                val socket = server.accept().also { client = it }
                val input = socket.getInputStream(); val output = socket.getOutputStream(); val packet = StringBuilder()
                while (true) {
                    val next = input.read(); if (next < 0) break
                    packet.append(next.toChar()); if (next.toChar() != '#') continue
                    val command = packet.toString(); packet.setLength(0); commands += command
                    val reply = when {
                        command == ":GVP#" -> "OnStep test#"
                        command == ":GR#" -> "05:00:00#"
                        command == ":GD#" -> "+40*00:00#"
                        command == ":Gt#" -> "+30*00#"
                        command == ":Gg#" -> "-120*00#"
                        command == ":GG#" -> "$offset#"
                        command == ":GC#" -> "$date#"
                        command == ":GL#" -> "$time#"
                        command.startsWith(":SG") -> if (rejectOffset) "0" else { offset = command.substring(3).dropLast(1); "1" }
                        command.startsWith(":SC") -> { date = command.substring(3).dropLast(1); "1" }
                        command.startsWith(":SL") -> { time = command.substring(3).dropLast(1); "1" }
                        else -> ""
                    }
                    if (reply.isNotEmpty()) { output.write(reply.toByteArray()); output.flush() }
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); client?.close(); worker.join(1000) }
    }
}
