package com.indigo.mobileobservatory.stacking

import java.util.concurrent.atomic.AtomicBoolean

/** Recheck collectible image garbage once per released workspace, without relaxing heap limits. */
internal class StackHeapAdmission(
    private val heapLimitBytes: Long,
    private val heapUsageBytes: () -> Long,
    private val collectGarbage: () -> Unit
) {
    private val mayCollect = AtomicBoolean(true)
    private val ceiling = StackMemoryBudget.forHeap(heapLimitBytes)
    private val planLock = Any()
    private var lastRequiredBytes = 0L

    fun available(retainedStackBytes: Long, requiredBytes: Long = 0, allowCollection: Boolean = true): Long {
        if (requiredBytes > 0) synchronized(planLock) {
            if (lastRequiredBytes != requiredBytes) {
                lastRequiredBytes = requiredBytes
                mayCollect.set(true)
            }
        }
        var nonStackBytes = (heapUsageBytes() - retainedStackBytes).coerceAtLeast(0)
        var budget = StackMemoryBudget.forHeap(heapLimitBytes, nonStackBytes)
        val pressure = if (requiredBytes > 0)
            requiredBytes <= ceiling && requiredBytes > budget
        else nonStackBytes > heapLimitBytes / 4
        if (allowCollection && pressure && mayCollect.compareAndSet(true, false)) {
            collectGarbage()
            nonStackBytes = (heapUsageBytes() - retainedStackBytes).coerceAtLeast(0)
            budget = StackMemoryBudget.forHeap(heapLimitBytes, nonStackBytes)
        }
        return budget
    }

    fun allocationsReleased() {
        mayCollect.set(true)
    }
}
