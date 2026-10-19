package com.indigo.mobileobservatory.stacking

/** Budget arrays against the VM's actual heap limit, not the phone's advertised RAM.
 * Reserve 60% for the rest of the app, transient allocations and GC headroom. */
internal object StackMemoryBudget {
    fun forHeap(heapLimitBytes: Long): Long {
        require(heapLimitBytes > 0)
        return heapLimitBytes / 5 * 2
    }
}
