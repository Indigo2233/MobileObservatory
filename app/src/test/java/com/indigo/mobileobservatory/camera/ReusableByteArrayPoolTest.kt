package com.indigo.mobileobservatory.camera

import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReusableByteArrayPoolTest {
    @Test
    fun repeatedFramesReuseOneAllocation() {
        val pool = ReusableByteArrayPool(capacity = 3)
        val first = pool.acquire(1024)
        pool.release(first)

        repeat(9_999) {
            val frame = pool.acquire(1024)
            assertSame(first, frame)
            pool.release(frame)
        }
    }

    @Test
    fun backpressureReturnsEveryDiscardedFrame() {
        val pool = ReusableByteArrayPool(capacity = 3)
        val slot = LatestFrameSlot<ByteArray>()
        val uniqueBuffers = Collections.newSetFromMap(
            IdentityHashMap<ByteArray, Boolean>()
        )

        repeat(10_000) { index ->
            val frame = pool.acquire(1024)
            uniqueBuffers.add(frame)
            slot.offer(frame)?.let(pool::release)
            if (index % 3 == 0) slot.takeLatest()?.let(pool::release)
        }
        slot.clear()?.let(pool::release)

        assertTrue(uniqueBuffers.size <= 2)
    }

    @Test
    fun frameSizeChangeDiscardsIncompatibleArray() {
        val pool = ReusableByteArrayPool(capacity = 2)
        val original = pool.acquire(1024)
        pool.release(original)

        val resized = pool.acquire(2048)

        assertTrue(resized.size == 2048)
        assertNotSame(original, resized)
    }
}
