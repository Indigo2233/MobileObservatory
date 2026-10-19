package com.indigo.mobileobservatory.recording

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class RecordingWriteOutcome(val failure: String? = null, val cause: Throwable? = null) {
    val succeeded: Boolean get() = failure == null
}

/** One worker owns writes, graceful draining and closing. A failed session can never recover
 * into success. Stop the producer before requestStop; a timed-out stop retains ownership until
 * the worker actually finishes, so another thread must never drain or close its writer. */
class RecordingWriteSession<T>(
    private val queue: BlockingQueue<T>,
    private val write: (T) -> Unit,
    private val release: (T) -> Unit,
    private val closeWriter: () -> Unit,
    private val onFailure: (RecordingWriteOutcome) -> Unit = {},
    private val onFinished: (RecordingWriteOutcome) -> Unit
) {
    private val lock = Any()
    private val done = CountDownLatch(1)
    private val completion = CompletableDeferred<RecordingWriteOutcome>()
    @Volatile private var stopping = false
    @Volatile private var failure: RecordingWriteOutcome? = null
    @Volatile private var terminal: RecordingWriteOutcome? = null
    private var started = false
    private val worker = Thread(::consume, "RecordWriter")

    val outcome: RecordingWriteOutcome? get() = terminal
    val isFinished: Boolean get() = done.count == 0L
    val hasFailed: Boolean get() = failure != null

    fun start() = synchronized(lock) {
        check(!started) { "Recording worker already started" }
        started = true
        worker.start()
    }

    fun requestStop() { stopping = true }

    /** Waiting or cancelling a waiter never changes the writer's outcome. The worker owns
     * draining and codec/filesystem deadlines, including when the UI has already returned. */
    suspend fun awaitCompletion(): RecordingWriteOutcome = completion.await()

    fun fail(reason: String, cause: Throwable? = null) {
        val reported = synchronized(lock) {
            if (terminal != null || failure != null) return
            RecordingWriteOutcome(reason, cause).also { failure = it; stopping = true }
        }
        onFailure(reported)
    }

    /** No interrupt is sent into a filesystem write. Timeout/caller interruption is sticky
     * failure, but cleanup stays on the worker after any currently blocked write returns. */
    fun awaitFinished(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        if (Thread.currentThread() === worker) return false
        return try {
            if (done.await(timeoutMillis, TimeUnit.MILLISECONDS)) true
            else { fail("recording_drain_timeout"); false }
        } catch (interrupted: InterruptedException) {
            fail("recording_drain_interrupted", interrupted)
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun releaseOwned(frame: T) {
        try { release(frame) }
        catch (error: Throwable) { fail("recording_release_failed", error) }
    }

    private fun consume() {
        try {
            while (failure == null && (!stopping || queue.isNotEmpty())) {
                val frame = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                try { write(frame) }
                catch (error: Throwable) {
                    fail(if (stopping) "recording_drain_failed" else "recording_write_failed", error)
                } finally { releaseOwned(frame) }
            }
        } catch (error: InterruptedException) {
            fail("recording_writer_interrupted", error)
        } catch (error: Throwable) {
            fail("recording_drain_failed", error)
        } finally {
            stopping = true
            // onFailure stops producers before returning. Every accepted buffer is released.
            while (true) releaseOwned(queue.poll() ?: break)
            try { closeWriter() }
            catch (error: Throwable) { fail("recording_close_failed", error) }
            val completed = synchronized(lock) {
                (failure ?: RecordingWriteOutcome()).also { terminal = it }
            }
            try { onFinished(completed) }
            finally { done.countDown(); completion.complete(completed) }
        }
    }
}
