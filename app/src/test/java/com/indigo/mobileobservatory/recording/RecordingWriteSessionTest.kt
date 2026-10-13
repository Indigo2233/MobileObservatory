package com.indigo.mobileobservatory.recording

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RecordingWriteSessionTest {
    @Test fun gracefulStopDrainsEveryAcceptedFrameBeforeCloseAndFinalSuccessOnTheSameWorker() {
        val queue = LinkedBlockingQueue<Int>().apply { addAll(listOf(1, 2, 3)) }
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val threads = Collections.synchronizedSet(mutableSetOf<Long>())
        val released = Collections.synchronizedList(mutableListOf<Int>())
        val completed = AtomicReference<RecordingWriteOutcome>()
        val session = RecordingWriteSession(queue, write = {
            threads += Thread.currentThread().id
            if (it == 1) { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            events += "write-$it"
        }, release = { released += it }, closeWriter = {
            threads += Thread.currentThread().id; events += "close"
        }, onFinished = { events += "finished"; completed.set(it) })
        session.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(3) { session.requestStop() }
            assertNull(completed.get()); assertFalse(session.isFinished)
            assertFalse(events.contains("close"))
            resume.countDown()
            assertTrue(session.awaitFinished(5000))
            assertTrue(completed.get().succeeded)
            assertEquals(listOf("write-1", "write-2", "write-3", "close", "finished"), events)
            assertEquals(listOf(1, 2, 3), released)
            assertEquals(1, threads.size)
            assertFalse(threads.contains(Thread.currentThread().id))
            session.requestStop(); assertTrue(session.awaitFinished(100))
            assertEquals(1, events.count { it == "close" })
        } finally { resume.countDown(); session.requestStop(); session.awaitFinished(5000) }
    }

    @Test fun writeFailureIsStickyEvenWhenDrainAndCloseWouldOtherwiseSucceed() {
        val queue = LinkedBlockingQueue<Int>().apply { addAll(listOf(1, 2, 3)) }
        val written = mutableListOf<Int>(); val released = mutableListOf<Int>()
        val closes = AtomicInteger(); val failures = AtomicInteger()
        val session = RecordingWriteSession(queue, write = {
            if (it == 2) throw IOException("disk full")
            written += it
        }, release = { released += it }, closeWriter = { closes.incrementAndGet() },
            onFailure = { failures.incrementAndGet() }, onFinished = {})
        session.start()
        assertTrue(session.awaitFinished(5000))
        assertEquals("recording_write_failed", session.outcome!!.failure)
        assertEquals(listOf(1), written)
        assertEquals(listOf(1, 2, 3), released)
        assertEquals(1, closes.get()); assertEquals(1, failures.get())
        repeat(2) { session.requestStop(); assertTrue(session.awaitFinished(100)) }
        assertFalse(session.outcome!!.succeeded)
    }

    @Test fun cancellationDuringDrainReleasesAllBuffersAndNeverFinalizesSuccessfully() {
        val queue = LinkedBlockingQueue<Int>().apply { addAll(listOf(1, 2)) }
        val released = mutableListOf<Int>(); var closes = 0
        val session = RecordingWriteSession(queue, write = { throw java.util.concurrent.CancellationException("cancel drain") },
            release = { released += it }, closeWriter = { closes++ }, onFinished = {})
        session.requestStop(); session.start()
        assertTrue(session.awaitFinished(5000))
        assertEquals("recording_drain_failed", session.outcome!!.failure)
        assertEquals(listOf(1, 2), released); assertEquals(1, closes)
    }

    @Test fun writerCloseFailureCannotBeReportedAsSuccessfulRecording() {
        val queue = LinkedBlockingQueue<Int>().apply { add(7) }
        val session = RecordingWriteSession(queue, write = {}, release = {},
            closeWriter = { throw IOException("trailer failed") }, onFinished = {})
        session.requestStop(); session.start()
        assertTrue(session.awaitFinished(5000))
        assertEquals("recording_close_failed", session.outcome!!.failure)
        assertEquals("trailer failed", session.outcome!!.cause!!.message)
    }

    @Test fun timedOutStopDoesNotCloseConcurrentlyWithBlockedWriteOrWriteRemainingFrames() {
        val queue = LinkedBlockingQueue<Int>().apply { addAll(listOf(1, 2)) }
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val closes = AtomicInteger(); val writes = AtomicInteger(); val released = mutableListOf<Int>()
        val session = RecordingWriteSession(queue, write = {
            writes.incrementAndGet(); entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
        }, release = { released += it }, closeWriter = { closes.incrementAndGet() }, onFinished = {})
        session.start()
        try {
            assertTrue(entered.await(5000, TimeUnit.MILLISECONDS)); session.requestStop()
            assertFalse(session.awaitFinished(10))
            assertTrue(session.hasFailed)
            assertNull(session.outcome); assertFalse(session.isFinished); assertEquals(0, closes.get())
            resume.countDown(); assertTrue(session.awaitFinished(5000))
            assertEquals("recording_drain_timeout", session.outcome!!.failure)
            assertEquals(1, writes.get()); assertEquals(1, closes.get())
            assertEquals(listOf(1, 2), released)
        } finally { resume.countDown(); session.requestStop(); session.awaitFinished(5000) }
    }

    @Test fun interruptionOfStopCallerMarksFailureWithoutInterruptingTheWriter() {
        val queue = LinkedBlockingQueue<Int>().apply { add(1) }
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val writerInterrupted = AtomicReference(false)
        val session = RecordingWriteSession(queue, write = {
            entered.countDown(); check(resume.await(5, TimeUnit.SECONDS))
            writerInterrupted.set(Thread.currentThread().isInterrupted)
        }, release = {}, closeWriter = {}, onFinished = {})
        session.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); session.requestStop()
            Thread.currentThread().interrupt()
            assertFalse(session.awaitFinished(100))
            assertTrue(Thread.interrupted()) // Restore the JUnit caller's interrupt status.
            resume.countDown(); assertTrue(session.awaitFinished(5000))
            assertEquals("recording_drain_interrupted", session.outcome!!.failure)
            assertFalse(writerInterrupted.get())
        } finally { Thread.interrupted(); resume.countDown(); session.requestStop(); session.awaitFinished(5000) }
    }
}
