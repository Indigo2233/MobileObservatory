package com.indigo.mobileobservatory.camera

/** A fresh SDK run must consume queued images before exposing scientific provenance.
 * The SDK callback must use a non-blocking read/readiness query. Only an explicit EMPTY
 * result establishes a boundary; errors and exhausted limits are not evidence of freshness.
 * After draining, discard one further complete image to avoid delivering a boundary image
 * that was already in transport. This is a host-side guard, not per-frame sensor metadata.
 */
class CaptureStartupBarrier {
    enum class Read { BUFFERED_FRAME, EMPTY, FAILED }

    private var discardBoundaryFrame = true

    fun drain(maxReads: Int = 32, maxNanos: Long = 250_000_000L,
              nanoTime: () -> Long = System::nanoTime, read: () -> Read): Boolean {
        require(maxReads > 0 && maxNanos > 0)
        val start = nanoTime()
        repeat(maxReads) {
            if (Thread.currentThread().isInterrupted || nanoTime() - start >= maxNanos) return false
            val result = try { read() } catch (_: Exception) { return false }
            if (nanoTime() - start >= maxNanos) return false
            when (result) {
                Read.EMPTY -> return true
                Read.FAILED -> return false
                Read.BUFFERED_FRAME -> Unit
            }
        }
        return false
    }

    /** Call only for complete SDK images, never for timeouts or errors. Worker-confined. */
    fun acceptCompleteFrame(): Boolean {
        if (discardBoundaryFrame) {
            discardBoundaryFrame = false
            return false
        }
        return true
    }
}
