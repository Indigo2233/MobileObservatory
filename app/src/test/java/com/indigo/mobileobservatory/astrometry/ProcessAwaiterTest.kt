package com.indigo.mobileobservatory.astrometry

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProcessAwaiterTest {
    private class FakeProcess(private val exits: Boolean = false) : Process() {
        val calls = AtomicInteger(); @Volatile var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { calls.incrementAndGet(); if (!exits) Thread.sleep(minOf(20, unit.toMillis(timeout))); return exits }
        override fun exitValue() = 0
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
    }
    @Test fun cancelStopsTheNativeSolverPromptly() = runBlocking {
        val process = FakeProcess()
        val work = launch { awaitSolverProcess(process, 360_000) }
        withTimeout(2000) { while (process.calls.get() == 0) delay(1) }
        withTimeout(2000) { work.cancelAndJoin() }
        assertTrue(process.destroyed)
        assertTrue(work.isCancelled)
    }
    @Test fun cancelBeforeTheWaitStartsStillDestroysTheLaunchedProcess() = runBlocking {
        val process = FakeProcess()
        val work = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            awaitSolverProcess(process, 360_000)
        }
        work.join()
        assertTrue(work.isCancelled)
        assertEquals(0, process.calls.get())
        assertTrue("The launched process survived cancellation before IO dispatch", process.destroyed)
    }
    @Test fun timeoutAndNormalCompletionRemainDifferent() = runBlocking {
        val timed = FakeProcess(); assertFalse(awaitSolverProcess(timed, 20)); assertTrue(timed.destroyed)
        val exited = FakeProcess(true); assertTrue(awaitSolverProcess(exited, 1000)); assertFalse(exited.destroyed)
    }
}
