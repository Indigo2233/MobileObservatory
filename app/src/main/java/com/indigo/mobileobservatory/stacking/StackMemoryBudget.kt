package com.indigo.mobileobservatory.stacking

/** Budget against the VM heap, including existing non-stack objects and a GC reserve.
 * Camera/native buffers are independent of the phone's advertised physical RAM. */
internal object StackMemoryBudget {
    fun forHeap(heapLimitBytes: Long, nonStackBytes: Long = 0): Long {
        require(heapLimitBytes > 0 && nonStackBytes >= 0)
        val reserve = maxOf(16L * 1024 * 1024, heapLimitBytes / 8)
        return minOf(heapLimitBytes / 5 * 4,
            (heapLimitBytes - minOf(nonStackBytes, heapLimitBytes) - reserve).coerceAtLeast(0))
    }
}
