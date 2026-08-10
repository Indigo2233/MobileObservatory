package com.indigo.mobileobservatory.camera

import java.util.concurrent.ArrayBlockingQueue

/**
 * Bounded pool for large frame arrays. Size changes discard incompatible arrays
 * instead of retaining multiple full-resolution generations.
 */
class ReusableByteArrayPool(capacity: Int) {
    private val buffers = ArrayBlockingQueue<ByteArray>(capacity.coerceAtLeast(1))

    fun acquire(size: Int): ByteArray {
        require(size >= 0)
        while (true) {
            val pooled = buffers.poll() ?: break
            if (pooled.size == size) return pooled
        }
        return ByteArray(size)
    }

    fun release(buffer: ByteArray) {
        buffers.offer(buffer)
    }

    fun clear() {
        buffers.clear()
    }
}
