package com.indigo.mobileobservatory.mount

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real module and protocol; all commands terminate at a local TCP simulator. */
class MountTrackingInstrumentedTest {
    private class TestApplication(base: Context) : Application() {
        private val prefix = "tracking-test-${System.nanoTime()}-"
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name, mode)
    }

    private class Mount(initialTracking: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val commands = CopyOnWriteArrayList<String>()
        @Volatile var tracking = initialTracking
        @Volatile var rejectEnable = false
        @Volatile var ignoreEnable = false
        @Volatile var invalidStatus = false
        @Volatile var holdEnable = false
        val enableReceived = CompletableDeferred<Unit>()
        val releaseEnable = CountDownLatch(1)
        @Volatile var client: Socket? = null
        private val worker = Thread {
            runCatching {
                val socket = server.accept().also { client = it }
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val buffer = StringBuilder()
                while (!socket.isClosed) {
                    val value = input.read()
                    if (value < 0) break
                    buffer.append(value.toChar())
                    if (value.toChar() != '#') continue
                    val command = buffer.toString()
                    buffer.clear()
                    commands += command
                    val reply = when (command) {
                        ":GR#" -> "10:00:00#"
                        ":GD#" -> "+20*00:00#"
                        ":GVP#" -> "OnStep tracking simulator#"
                        ":Gt#", ":Gg#" -> "+30*00:00#"
                        ":GU#" -> if (invalidStatus) "0#" else if (tracking) "NpEW160#" else "nNpEW160#"
                        ":GT#" -> if (tracking) "60.16427#" else "0.00000#"
                        ":Te#" -> {
                            enableReceived.complete(Unit)
                            if (holdEnable) releaseEnable.await(5, TimeUnit.SECONDS)
                            if (!rejectEnable && !ignoreEnable) tracking = true
                            if (rejectEnable) "0" else "1"
                        }
                        ":Td#" -> { tracking = false; "1" }
                        ":hC#" -> { tracking = false; "" }
                        ":MS#" -> "0" // Firmware that needs tracking explicitly enabled.
                        else -> when {
                            command.startsWith(":SG") -> "0" // Clock writes are irrelevant to these tests.
                            command.startsWith(":Sr") || command.startsWith(":Sd") -> "1"
                            else -> ""
                        }
                    }
                    if (reply.isNotEmpty()) { output.write(reply.toByteArray()); output.flush() }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() {
            releaseEnable.countDown()
            runCatching { client?.close() }
            server.close()
            worker.join(1000)
        }
    }

    private suspend fun withMount(initialTracking: Boolean, block: suspend (MountModule, Mount) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val application = TestApplication(ApplicationProvider.getApplicationContext())
        Mount(initialTracking).use { fake ->
            val module = MountModule(application, scope)
            try {
                module.setMountTransport(MountTransportType.TCP)
                module.setMountProtocol(MountProtocolType.LX200_ONSTEP)
                module.setMountHost("127.0.0.1")
                module.setMountPort(fake.port.toString())
                module.connectMount()
                withTimeout(8000) { module.mountConnectionState.first { it is MountConnectionState.Connected } }
                block(module, fake)
            } finally {
                module.cancelJobs()
                scope.cancel()
                module.closeController()
            }
        }
    }

    @Test fun connectingReadsAlreadyEnabledTrackingWithoutChangingIt() = runBlocking {
        withMount(true) { module, fake ->
            assertEquals("Connecting must observe tracking already enabled by the mount", true, module.mountTrackingEnabled.value)
            assertTrue(fake.commands.contains(":GU#"))
            assertFalse(fake.commands.any { it in setOf(":Te#", ":Td#", ":TQ#", ":TS#", ":TL#") })
        }
    }

    @Test fun gotoFromStoppedTrackingEnablesItOnceAndReadsBack() = runBlocking {
        withMount(false) { module, fake ->
            module.gotoMountTarget("test", 10.0, 20.0)
            withTimeout(8000) { module.motionState.first { !it.isActive } }
            assertTrue("GOTO completion must leave tracking enabled", fake.tracking)
            assertEquals(true, module.mountTrackingEnabled.value)
            assertEquals(1, fake.commands.count { it == ":Te#" })
            assertTrue(fake.commands.lastIndexOf(":GU#") > fake.commands.indexOf(":Te#"))
        }
    }

    @Test fun connectingToStoppedMountOnlyReadsState() = runBlocking {
        withMount(false) { module, fake ->
            assertEquals(false, module.mountTrackingEnabled.value)
            assertEquals(MountTrackingRate.OFF, module.mountTrackingRate.value)
            assertFalse(fake.commands.contains(":Te#"))
        }
    }

    @Test fun homeReadsStoppedTrackingAndTheFollowingGotoEnablesAgain() = runBlocking {
        withMount(true) { module, fake ->
            module.goMountHome()
            withTimeout(8000) { module.motionState.first { !it.isActive } }
            assertFalse(fake.tracking)
            assertEquals(false, module.mountTrackingEnabled.value)
            assertEquals(MountTrackingRate.OFF, module.mountTrackingRate.value)
            assertTrue(fake.commands.lastIndexOf(":GU#") > fake.commands.indexOf(":hC#"))
            assertFalse(fake.commands.contains(":Te#"))
            module.gotoMountTarget("test", 10.0, 20.0)
            withTimeout(8000) { module.motionState.first { !it.isActive } }
            assertTrue(fake.tracking)
            assertEquals(1, fake.commands.count { it == ":Te#" })
        }
    }

    @Test fun requiredTrackingEnablesOnceAndSubsequentChecksOnlyRead() = runBlocking {
        withMount(false) { module, fake ->
            module.ensureMountTracking()
            module.ensureMountTracking()
            assertEquals(true, module.mountTrackingEnabled.value)
            assertEquals(1, fake.commands.count { it == ":Te#" })
            assertTrue(fake.commands.lastIndexOf(":GU#") > fake.commands.indexOf(":Te#"))
        }
    }

    @Test fun acceptingEnableWithoutActuallyTrackingFailsVerificationAndNeverStartsRa() = runBlocking {
        withMount(false) { module, fake ->
            fake.ignoreEnable = true
            module.moveMountRaBy(10.0, true, 3.0)
            withTimeout(8000) { module.motionState.first { !it.isActive } }
            assertEquals(false, module.mountTrackingEnabled.value)
            assertEquals(1, fake.commands.count { it == ":Te#" })
            assertFalse(fake.commands.any { it in setOf(":Me#", ":Mw#") })
            assertTrue(module.mountMoveStatus.value.contains("tracking could not be confirmed"))
        }
    }

    @Test fun raMoveChecksTrackingBeforeItStarts() = runBlocking {
        withMount(false) { module, fake ->
            module.moveMountRaBy(10.0, true, 3.0)
            withTimeout(5000) { while (":Me#" !in fake.commands) delay(20) }
            assertTrue(fake.commands.indexOf(":Te#") < fake.commands.indexOf(":Me#"))
            assertTrue(fake.commands.subList(fake.commands.indexOf(":Te#") + 1, fake.commands.indexOf(":Me#")).contains(":GU#"))
            module.stopMountMotion()
            withTimeout(5000) { module.motionState.first { !it.isActive } }
            assertEquals(1, fake.commands.count { it == ":Te#" })
        }
    }

    @Test fun externalTrackingChangesAreObservedWithoutAnyTrackingWrites() = runBlocking {
        withMount(true) { module, fake ->
            fake.tracking = false
            withTimeout(6000) { module.mountTrackingEnabled.first { it == false } }
            fake.tracking = true
            withTimeout(6000) { module.mountTrackingEnabled.first { it == true } }
            assertFalse(fake.commands.any { it in setOf(":Te#", ":Td#") })
        }
    }

    @Test fun readOnlyPreservesExternallyEnabledTrackingAndRejectsAutomaticEnable() = runBlocking {
        withMount(true) { module, fake ->
            module.setMountAccessMode(MountAccessMode.READ_ONLY)
            delay(500)
            assertTrue(fake.tracking)
            assertEquals(true, module.mountTrackingEnabled.value)
            try { module.ensureMountTracking(); fail("Read-only automatically enabled tracking") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("read-only")) }
            assertFalse(fake.commands.any { it in setOf(":Te#", ":Td#") })
        }
    }

    @Test fun enteringReadOnlyStopsOnlyTrackingStartedByTheApplicationAndReadsBack() = runBlocking {
        withMount(false) { module, fake ->
            module.ensureMountTracking()
            module.setMountAccessMode(MountAccessMode.READ_ONLY)
            withTimeout(6000) { module.mountTrackingEnabled.first { it == false } }
            assertFalse(fake.tracking)
            assertEquals(1, fake.commands.count { it == ":Td#" })
        }
    }

    @Test fun readOnlyTransitionDuringAnEnableAcknowledgementStillStopsApplicationTracking() = runBlocking {
        withMount(false) { module, fake ->
            fake.holdEnable = true
            val enable = async { module.ensureMountTracking() }
            withTimeout(5000) { fake.enableReceived.await() }
            module.setMountAccessMode(MountAccessMode.READ_ONLY)
            fake.releaseEnable.countDown()
            enable.await()
            withTimeout(6000) {
                while (":Td#" !in fake.commands) delay(20)
                module.mountTrackingEnabled.first { it == false }
            }
            assertFalse(fake.tracking)
            assertEquals(1, fake.commands.count { it == ":Td#" })
        }
    }

    @Test fun invalidReadbackBecomesUnknownAndDoesNotBlindlyEnableTracking() = runBlocking {
        withMount(true) { module, fake ->
            fake.invalidStatus = true
            try { module.ensureMountTracking(); fail("Invalid status was treated as confirmed tracking") }
            catch (_: IllegalStateException) {}
            assertNull(module.mountTrackingEnabled.value)
            assertNull(module.mountTrackingRate.value)
            assertFalse(fake.commands.contains(":Te#"))
        }
    }

    @Test fun disconnectClearsThePreviousDevicesTrackingState() = runBlocking {
        withMount(true) { module, _ ->
            module.disconnectMount()
            withTimeout(5000) { module.mountConnectionState.first { it is MountConnectionState.Disconnected } }
            assertNull(module.mountTrackingEnabled.value)
            assertNull(module.mountTrackingRate.value)
        }
    }
}
