package com.indigo.mobileobservatory.util

import org.junit.Assert.*
import org.junit.Test

class UncaughtCrashLoggerTest {
    @Test fun recordsFullCauseAndSuppressedStacksBeforeCallingThePlatformHandler() {
        val cause = IllegalArgumentException("guide layout failed")
        val failure = IllegalStateException("composition failed", cause).apply {
            addSuppressed(IllegalStateException("cleanup failed"))
        }
        val events = mutableListOf<String>()
        var delegated: Throwable? = null
        val thread = Thread("guide-entry")
        val logger = UncaughtCrashLogger({ events += it }, { receivedThread, receivedFailure ->
            assertSame(thread, receivedThread)
            delegated = receivedFailure
            events += "platform handler"
        })
        logger.uncaughtException(thread, failure)
        assertEquals(2, events.size)
        assertTrue(events.first().contains("guide-entry"))
        assertTrue(events.first().contains("Caused by: java.lang.IllegalArgumentException: guide layout failed"))
        assertTrue(events.first().contains("Suppressed: java.lang.IllegalStateException: cleanup failed"))
        assertSame(failure, delegated)
        assertEquals("platform handler", events.last())
    }

    @Test fun failedLogWriteStillCallsPlatformHandlerExactlyOnceWithOriginalFailure() {
        var calls = 0
        val failure = IllegalStateException("original guide crash")
        UncaughtCrashLogger({ throw OutOfMemoryError("cannot write") }, { _, received ->
            assertSame(failure, received)
            calls++
        }).uncaughtException(Thread.currentThread(), failure)
        assertEquals(1, calls)
    }
}
