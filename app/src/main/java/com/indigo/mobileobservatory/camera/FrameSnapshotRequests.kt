package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        /**
         * The caller already owns the frame it needs. No camera frame is awaited,
         * and a later frame must not overwrite the held request.
         */
        var satisfied = false
    }

    private val lock = Any()
    private val serial = Mutex()
    private var generation = 0L
    private var cancellationReason = "Camera acquisition stopped"
    private var current: Request<T>? = null
    private val _state = MutableStateFlow<State?>(null)

    /**
     * The occupied slot, published as a flow. A short save can occupy and release
     * the slot between two reads, so a caller that must observe the occupancy
     * collects this flow instead of polling.
     */
    val state: StateFlow<State?> = _state.asStateFlow()

    /** The occupied slot, read once. */
    val currentState: State? get() = _state.value

    /** Publishes the current slot under [lock]. */
    private fun publishState() {
        _state.value = current?.let { State(it.owner, when {
            it.satisfied -> Phase.COPYING
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
                Request<T>(owner).also {
                    current = it
                    publishState()
                }
            }
            try {
                val result = withTimeoutOrNull(timeoutMs) { request.result.await() }
                synchronized(lock) {
                    if (requestedGeneration != generation) throw CancellationException(cancellationReason)
                }
                result
            } finally {
                synchronized(lock) {
                    if (current === request) {
                        current = null
                        publishState()
                    }
                    request.result.cancel()
                }
            }
        }
    }

    /**
     * Holds the request slot while the caller writes a file. The slot stays visible
     * to the other capture paths, which use it to refuse a conflicting request, and
     * it is released as soon as the caller finishes.
     *
     * When [alreadySatisfied] is true the caller owns the frame it needs, so no
     * camera frame is awaited and the block receives null. Otherwise a camera frame
     * is awaited first and the block receives it.
     */
    suspend fun <R> hold(owner: String, timeoutMs: Long, checkReady: () -> Unit,
        alreadySatisfied: () -> Boolean, block: suspend (T?) -> R): R {
        val requestedGeneration = synchronized(lock) { generation }
        return serial.withLock {
            val request = synchronized(lock) {
                if (requestedGeneration != generation) throw CancellationException(cancellationReason)
                checkReady()
                check(current == null)
                Request<T>(owner).also {
                    current = it
                    publishState()
                }
            }
            try {
                val owned = alreadySatisfied()
                val delivered = if (owned) {
                    // The caller owns its frame. The request stays open for the
                    // duration of the write and must not take a later frame.
                    synchronized(lock) {
                        request.satisfied = true
                        publishState()
                    }
                    null
                } else {
                    withTimeoutOrNull(timeoutMs) { request.result.await() }
                }
                synchronized(lock) {
                    if (requestedGeneration != generation) throw CancellationException(cancellationReason)
                }
                block(delivered)
            } finally {
                synchronized(lock) {
                    if (current === request) {
                        current = null
                        publishState()
                    }
                    request.result.cancel()
                }
            }
        }
    }

    fun cancel(reason: String): State? = synchronized(lock) {
        val previous = _state.value
        generation++
        cancellationReason = reason
        val request = current
        current = null
        publishState()
        request?.result?.cancel(CancellationException(reason))
        previous
    }

    /** Keep the request visible during copying so pause can still cancel it. */
    fun fulfill(accept: () -> Boolean, snapshot: () -> T) {
        val request = synchronized(lock) {
            current?.takeIf { it.result.isActive && !it.copying && !it.satisfied && accept() }
                ?.also {
                    it.copying = true
                    publishState()
                }
        } ?: return
        try {
            val owned = snapshot()
            synchronized(lock) {
                if (current === request) {
                    request.result.complete(owned)
                    publishState()
                }
            }
        } catch (error: Throwable) {
            synchronized(lock) {
                if (current === request) {
                    request.result.completeExceptionally(error)
                    publishState()
                }
            }
        }
    }
}
