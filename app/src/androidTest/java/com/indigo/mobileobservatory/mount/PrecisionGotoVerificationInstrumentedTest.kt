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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Real module/runner/protocol, talking only to a loopback fake mount; never physical hardware. */
class PrecisionGotoVerificationInstrumentedTest {
    private class TestApplication(base: Context) : Application() {
        private val prefix = "mount-test-${System.nanoTime()}-"
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
    }
    private class LoopbackMount : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val events = Collections.synchronizedList(mutableListOf<String>())
        private val abortReceived = CompletableDeferred<Unit>()
        suspend fun awaitAbortReceived() = withTimeout(5000) { abortReceived.await() }
        @Volatile private var client: Socket? = null
        private val worker = Thread {
            runCatching {
                val socket = server.accept().also { client = it }
                val input = socket.getInputStream(); val output = socket.getOutputStream(); val command = StringBuilder()
                while (!socket.isClosed) {
                    val value = input.read(); if (value < 0) break
                    command.append(value.toChar())
                    if (value.toChar() != '#') continue
                    val text = command.toString(); command.clear(); events += text
                    if (text == ":Q#") abortReceived.complete(Unit)
                    val reply = when {
                        text == ":GR#" -> "10:00:00#"
                        text == ":GD#" -> "+20*00:00#"
                        text == ":GVP#" -> "WF test mount#"
                        text == ":Gt#" || text == ":Gg#" -> "+30*00:00#"
                        text.startsWith(":Sr") || text.startsWith(":Sd") || text == ":Te#" || text == ":Td#" -> "1"
                        text == ":MS#" -> "0"
                        text == ":CM#" -> "Coordinates matched#"
                        else -> ""
                    }
                    if (reply.isNotEmpty()) { output.write(reply.toByteArray()); output.flush() }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { runCatching { client?.close() }; server.close(); worker.join(1000) }
    }
    /** Holds scheduled operation bodies while letting an explicitly posted STOP run first. */
    private class PausableDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private var paused = false
        private val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val held = synchronized(lock) {
                if (paused) { pending.addLast(context to block); true } else false
            }
            if (!held) Dispatchers.Default.dispatch(context, block)
        }
        fun pause() { synchronized(lock) { paused = true } }
        fun runPosted(action: () -> Unit) {
            val posted = synchronized(lock) {
                check(paused)
                action()
                pending.removeLast()
            }
            posted.second.run()
        }
        fun resume() {
            val held = synchronized(lock) {
                paused = false
                pending.toList().also { pending.clear() }
            }
            held.forEach { (context, block) -> Dispatchers.Default.dispatch(context, block) }
        }
    }
    private suspend fun withMount(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        block: suspend (MountModule, LoopbackMount) -> Unit
    ) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val application = TestApplication(ApplicationProvider.getApplicationContext())
        LoopbackMount().use { fake ->
            val module = MountModule(application, scope)
            try {
                module.setMountTransport(MountTransportType.TCP); module.setMountProtocol(MountProtocolType.LX200_ONSTEP)
                module.setMountHost("127.0.0.1"); module.setMountPort(fake.port.toString()); module.connectMount()
                withTimeout(8000) { module.mountConnectionState.first { it is MountConnectionState.Connected } }
                block(module, fake)
            } finally { module.cancelJobs(); module.closeController(); scope.cancel() }
        }
    }
    @Test fun syncSuccessRequiresAnotherOpticalExposureNotMatchingReadback() = runBlocking {
        withMount { module, fake ->
            val captures = AtomicInteger()
            assertTrue(module.startPrecisionGoto("test", 10.0, 20.0) {
                val count = captures.incrementAndGet(); fake.events += "optical-$count"; MountCoordinates(10.0, 20.0)
            })
            val final = withTimeout(12000) { module.precisionGotoProgress.first { it.phase == PrecisionGotoPhase.SUCCEEDED || it.phase == PrecisionGotoPhase.FAILED } }
            assertEquals(final.message, PrecisionGotoPhase.SUCCEEDED, final.phase)
            assertEquals(2, captures.get())
            assertTrue(fake.events.indexOf(":CM#") in (fake.events.indexOf("optical-1") + 1) until fake.events.indexOf("optical-2"))
        }
    }
    @Test fun wrongOpticalResultAfterSyncFailsEvenWhenMountReadbackIsExactlyTarget() = runBlocking {
        withMount { module, _ ->
            val captures = AtomicInteger()
            assertTrue(module.startPrecisionGoto("test", 10.0, 20.0) {
                if (captures.incrementAndGet() == 1) MountCoordinates(10.0, 20.0) else MountCoordinates(10.0, 22.0)
            })
            val final = withTimeout(12000) { module.precisionGotoProgress.first { it.phase == PrecisionGotoPhase.SUCCEEDED || it.phase == PrecisionGotoPhase.FAILED } }
            assertEquals(PrecisionGotoPhase.FAILED, final.phase)
            assertEquals(2, captures.get())
        }
    }
    @Test fun stopCancelsPostSyncVerificationAndNeverLeavesAnActiveOrSuccessfulTask() = runBlocking {
        withMount { module, fake ->
            val captures = AtomicInteger(); val verifying = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
            assertTrue(module.startPrecisionGoto("test", 10.0, 20.0) {
                if (captures.incrementAndGet() > 1) {
                    verifying.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                MountCoordinates(10.0, 20.0)
            })
            withTimeout(12000) { verifying.await() }
            assertTrue(module.motionState.value.showsGlobalStop)
            module.stopMountMotion()
            withTimeout(5000) { cancelled.await(); module.precisionGotoProgress.first { it.phase == PrecisionGotoPhase.CANCELLED } }
            assertFalse(module.precisionGotoProgress.value.isActive)
            fake.awaitAbortReceived()
            assertTrue(fake.events.contains(":Q#"))
        }
    }

    @Test fun precisionExposureWaitsForThreeSecondsOfSettling() = runBlocking {
        withMount { module, _ ->
            val settlingAt = CompletableDeferred<Long>()
            val observing = launch(start = CoroutineStart.UNDISPATCHED) {
                module.precisionGotoProgress.first { it.phase == PrecisionGotoPhase.SETTLING }
                settlingAt.complete(android.os.SystemClock.elapsedRealtime())
            }
            val captured = CompletableDeferred<Long>()
            assertTrue(module.startPrecisionGoto("settling", 10.0, 20.0) {
                captured.complete(android.os.SystemClock.elapsedRealtime())
                awaitCancellation()
            })
            try {
                val time = withTimeout(15_000) { captured.await() }
                assertTrue("Exposure started before a three-second quiet period", time - settlingAt.await() >= 3000)
            } finally {
                module.stopMountMotion()
                withTimeout(5000) { module.motionState.first { !it.isActive } }
                observing.cancelAndJoin()
            }
        }
    }

    @Test fun stopDuringTheFirstSolveKillsItsNativeProcessAndPreventsFurtherSlews() = runBlocking {
        withMount { module, fake ->
            val entered = CompletableDeferred<Process>()
            val cancelled = CompletableDeferred<Unit>()
            val captures = AtomicInteger()
            assertTrue(module.startPrecisionGoto("cancel solver", 10.0, 20.0) {
                captures.incrementAndGet()
                val process = ProcessBuilder("/system/bin/sleep", "300").start()
                entered.complete(process)
                try {
                    com.indigo.mobileobservatory.astrometry.awaitSolverProcess(process, 360_000)
                    error("Unexpected solver completion")
                } finally { cancelled.complete(Unit) }
            })
            val process = withTimeout(15_000) { entered.await() }
            try {
                assertEquals(PrecisionGotoPhase.SOLVING, module.precisionGotoProgress.value.phase)
                module.stopMountMotion()
                withTimeout(3000) { cancelled.await() }
                assertTrue(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
                withTimeout(5000) { module.precisionGotoProgress.first { it.phase == PrecisionGotoPhase.CANCELLED } }
                assertEquals(1, captures.get())
                assertEquals(1, fake.events.count { it == ":MS#" })
            } finally { process.destroyForcibly() }
        }
    }

    @Test fun automaticCalibrationKeepsStopDuringEachCaptureAndNeverWritesSync() = runBlocking {
        withMount { module, fake ->
            withTimeout(15_000) {
                module.runCalibrationMotion { slew ->
                    repeat(2) { sample ->
                        slew("sample-$sample", MountCoordinates(10.0, 20.0))
                        assertTrue(module.motionState.value.showsGlobalStop)
                        assertNotNull(module.motionState.value.ownerToken)
                        fake.events += "calibration-capture-$sample"
                    }
                }
                module.motionState.first { !it.isActive }
            }
            assertEquals(2, fake.events.count { it == ":MS#" })
            assertFalse(fake.events.contains(":CM#"))
            assertTrue(fake.events.indexOf("calibration-capture-0") < fake.events.indexOf("calibration-capture-1"))
        }
    }
    @Test fun automaticCalibrationAcquisitionFailureStopsBeforeTheNextGoto() = runBlocking {
        withMount { module, fake ->
            try {
                withTimeout(15_000) {
                    module.runCalibrationMotion { slew ->
                        repeat(2) { sample ->
                            slew("sample-$sample", MountCoordinates(10.0, 20.0))
                            throw IllegalStateException("No independent optical observation")
                        }
                    }
                }
                fail("Acquisition failure was swallowed")
            } catch (expected: IllegalStateException) {
                assertEquals("No independent optical observation", expected.message)
            }
            withTimeout(5000) { module.motionState.first { !it.isActive } }
            assertEquals(1, fake.events.count { it == ":MS#" })
            fake.awaitAbortReceived()
            assertTrue(fake.events.contains(":Q#"))
            assertFalse(fake.events.contains(":CM#"))
        }
    }
    @Test fun stopCancelsAutomaticCalibrationAcquisitionAndPreventsAllFollowingGoto() = runBlocking {
        withMount { module, fake ->
            val acquiring = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
            val task = launch {
                module.runCalibrationMotion { slew ->
                    slew("training", MountCoordinates(10.0, 20.0))
                    acquiring.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                    @Suppress("UNREACHABLE_CODE")
                    slew("verification", MountCoordinates(10.0, 20.0))
                }
            }
            withTimeout(12_000) { acquiring.await() }
            assertTrue(module.motionState.value.showsGlobalStop)
            module.stopMountMotion()
            withTimeout(5000) { cancelled.await(); task.join(); module.motionState.first { !it.isActive } }
            assertEquals(1, fake.events.count { it == ":MS#" })
            fake.awaitAbortReceived()
            assertTrue(fake.events.contains(":Q#"))
            assertFalse(fake.events.contains(":CM#"))
        }
    }

    @Test fun stopBeforeAutomaticCalibrationBodyStartsCancelsItsCallerWithoutHanging() = runBlocking {
        val dispatcher = PausableDispatcher()
        withMount(dispatcher) { module, fake ->
            var bodyRan = false
            dispatcher.pause()
            try {
                val caller = async(start = CoroutineStart.UNDISPATCHED) {
                    module.runCalibrationMotion { bodyRan = true }
                }
                assertNotNull(module.motionState.value.ownerToken)
                assertFalse(bodyRan)
                dispatcher.runPosted { module.stopMountMotion() }
                fake.awaitAbortReceived()
                dispatcher.resume()
                withTimeout(5000) { caller.join(); module.motionState.first { !it.isActive } }
                assertTrue(caller.isCancelled)
                assertFalse(bodyRan)
                // One controller abort intentionally sends an immediate emergency Q, then a
                // synchronized Q after draining pending replies. Runner exactly-once ownership
                // is asserted separately with a delayed abort callback in MountMotionRunnerTest.
                withTimeout(5000) { while (fake.events.count { it == ":Q#" } < 2) delay(10) }
                assertEquals(2, fake.events.count { it == ":Q#" })
                assertFalse(fake.events.contains(":MS#"))
                assertFalse(fake.events.contains(":CM#"))
            } finally { dispatcher.resume() }
        }
    }
}
