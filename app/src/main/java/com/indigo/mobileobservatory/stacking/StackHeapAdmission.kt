package com.indigo.mobileobservatory.stacking

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recheck collectible image garbage once per released workspace, without relaxing heap limits.
 *
 * The budget is derived from the live heap usage, so a workspace whose arrays are still
 * reachable shrinks the next session's allowance. A release therefore collects once and
 * reports the recovered budget, instead of leaving the next plan smaller than the last.
 */
internal class StackHeapAdmission(
    private val heapLimitBytes: Long,
    private val heapUsageBytes: () -> Long,
    private val collectGarbage: () -> Unit
) {
    private val mayCollect = AtomicBoolean(true)
    private val ceiling = StackMemoryBudget.forHeap(heapLimitBytes)
    private val planLock = Any()
    private var lastRequiredBytes = 0L
    /** Set when a workspace released its arrays; the next sample must re-collect once. */
    @Volatile private var staleUsage = false

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
        // A released workspace makes the previous sample stale. Re-collect once even when
        // the plan is unchanged, otherwise the budget keeps measuring the retired
        // session's arrays and the next ROI comes out smaller than the last one.
        val released = staleUsage
        if (released) mayCollect.set(true)
        if (allowCollection && (pressure || released) && mayCollect.compareAndSet(true, false)) {
            collectGarbage()
            nonStackBytes = (heapUsageBytes() - retainedStackBytes).coerceAtLeast(0)
            budget = StackMemoryBudget.forHeap(heapLimitBytes, nonStackBytes)
            staleUsage = false
        }
        return budget
    }

    /**
     * The workspace released its arrays. The next sample collects once and reports the
     * recovered budget, so the next session is planned against the released heap.
     */
    fun allocationsReleased() {
        staleUsage = true
        mayCollect.set(true)
    }
}
