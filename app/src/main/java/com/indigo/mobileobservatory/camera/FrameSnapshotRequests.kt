package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Serializes still snapshots and cancels the whole waiting generation when acquisition stops. */
internal class FrameSnapshotRequests<T> {
    enum class Phase { WAITING, COPYING, DELIVERED }
    data class State(val owner: String, val phase: Phase)

    private class Request<T>(val owner: String) {
        val result = CompletableDeferred<T>()
        var copying = false
    }

    private val lock = Any()
    private val serial = Mutex()
    private var generation = 0L
    private var cancellationReason = "Camera acquisition stopped"
    private var current: Request<T>? = null

    val state: State?
        get() = synchronized(lock) {
            current?.let { State(it.owner, when {
                it.result.isCompleted -> Phase.DELIVERED
                it.copying -> Phase.COPYING
                else -> Phase.WAITING
            }) }
        }

    suspend fun await(owner: String, timeoutMs: Long, checkReady: () -> Unit): T? {
        val requestedGeneration = synchronized(lock) { generation }
        return serial.withLock {
            val request = synchronized(lock) {
                if (requestedGeneration != generation) throw CancellationException(cancellationReason)
                checkReady()
                check(current == null)
                Request<T>(owner).also { current = it }
            }
            try {
                val result = withTimeoutOrNull(timeoutMs) { request.result.await() }
                synchronized(lock) {
                    if (requestedGeneration != generation) throw CancellationException(cancellationReason)
                }
                result
            } finally {
                synchronized(lock) {
                    if (current === request) current = null
                    request.result.cancel()
                }
            }
        }
    }

    fun cancel(reason: String): State? = synchronized(lock) {
        val previous = state
        generation++
        cancellationReason = reason
        val request = current
        current = null
        request?.result?.cancel(CancellationException(reason))
        previous
    }

    /** Keep the request visible during copying so pause can still cancel it. */
    fun fulfill(accept: () -> Boolean, snapshot: () -> T) {
        val request = synchronized(lock) {
            current?.takeIf { it.result.isActive && !it.copying && accept() }
                ?.also { it.copying = true }
        } ?: return
        try {
            val owned = snapshot()
            synchronized(lock) {
                if (current === request) request.result.complete(owned)
            }
        } catch (error: Throwable) {
            synchronized(lock) {
                if (current === request) request.result.completeExceptionally(error)
            }
        }
    }
}
