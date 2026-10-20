package com.indigo.mobileobservatory.stacking

/** Budget arrays against the VM's actual heap limit, not the phone's advertised RAM.
 * The manifest declares `android:largeHeap`, so the limit is dalvik.vm.heapsize (512 MiB on
 * 16 GB phones) instead of dalvik.vm.heapgrowthlimit. Take 60% for a single stacking pass and
 * keep the rest for the live view, camera callbacks, preview bitmaps and GC headroom. */
internal object StackMemoryBudget {
    fun forHeap(heapLimitBytes: Long): Long {
        require(heapLimitBytes > 0)
        return heapLimitBytes / 5 * 3
    }
}
