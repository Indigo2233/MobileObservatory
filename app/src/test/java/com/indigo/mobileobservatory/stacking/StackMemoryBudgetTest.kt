package com.indigo.mobileobservatory.stacking

import org.junit.Assert.*
import org.junit.Test

class StackMemoryBudgetTest {
    @Test fun existingObjectsAndCollectionReserveReduceTheAdmissionBudget() {
        val heap = 512L shl 20
        val ceiling = StackMemoryBudget.forHeap(heap)
        assertEquals(heap / 5 * 4, ceiling)
        assertEquals(352L shl 20, StackMemoryBudget.forHeap(heap, 96L shl 20))
        assertEquals(0L, StackMemoryBudget.forHeap(heap, 500L shl 20))
        assertEquals(0L, StackMemoryBudget.forHeap(heap, Long.MAX_VALUE))
        assertTrue(StackMemoryBudget.forHeap(64L shl 20) <= 48L shl 20)
    }

    @Test fun allAdmittedPlansFitTheirHeapIncludingExistingObjectsAndReserve() {
        for (heapMiB in listOf(64, 128, 256, 512, 1024)) {
            val heap = heapMiB.toLong() shl 20
            for (usedMiB in 0 until heapMiB step 11) {
                val used = usedMiB.toLong() shl 20
                val budget = StackMemoryBudget.forHeap(heap, used)
                if (budget > 0) assertTrue(budget + used + maxOf(16L shl 20, heap / 8) <= heap)
            }
        }
    }
}
