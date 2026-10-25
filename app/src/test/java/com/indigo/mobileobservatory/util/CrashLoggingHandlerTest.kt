package com.indigo.mobileobservatory.util

import org.junit.Assert.*
import org.junit.Test

class CrashLoggingHandlerTest {
    @Test fun completeCauseChainIsLoggedBeforeDelegatingTheOriginalCrash() {
        val calls = mutableListOf<String>()
        val thread = Thread("guide-entry-test")
        val error = IllegalStateException("outer", IllegalArgumentException("root cause")).apply {
            addSuppressed(UnsupportedOperationException("suppressed detail"))
        }
        val handler = CrashLoggingHandler(Thread.UncaughtExceptionHandler { actualThread, actualError ->
            assertSame(thread, actualThread); assertSame(error, actualError)
            calls += "delegate"
        }) { calls += it }
        handler.uncaughtException(thread, error)
        assertEquals(2, calls.size)
        assertTrue(calls[0].contains("guide-entry-test"))
        assertTrue(calls[0].contains("root cause"))
        assertTrue(calls[0].contains("suppressed detail"))
        assertEquals("delegate", calls[1])
    }

    @Test fun failedLoggingStillDelegatesExactlyOnce() {
        val error = IllegalStateException("original")
        var delegates = 0
        val handler = CrashLoggingHandler(Thread.UncaughtExceptionHandler { _, actual ->
            assertSame(error, actual); delegates++
        }) { throw IllegalStateException("disk unavailable") }
        handler.uncaughtException(Thread.currentThread(), error)
        assertEquals(1, delegates)
    }
}
