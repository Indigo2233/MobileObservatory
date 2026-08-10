package com.indigo.mobileobservatory.camera

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Lock-free one-element backpressure seam: a new value replaces an unconsumed old value. */
class LatestFrameSlot<T> {
    private val pending = AtomicReference<T?>(null)
    private val _received = AtomicLong(0)
    private val _dropped = AtomicLong(0)

    val received: Long get() = _received.get()
    val dropped: Long get() = _dropped.get()

    /**
     * Stores [value] and returns the unconsumed value it replaced, if any.
     * The caller owns the returned value and must release its resources.
     */
    fun offer(value: T): T? {
        _received.incrementAndGet()
        return pending.getAndSet(value)?.also { _dropped.incrementAndGet() }
    }

    fun takeLatest(): T? = pending.getAndSet(null)

    /** Removes and returns the pending value so its owner can release it. */
    fun clear(): T? = pending.getAndSet(null)
}
