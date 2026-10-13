package com.indigo.mobileobservatory.pointing

import org.junit.Assert.*
import org.junit.Test

class AuxiliaryCaptureMemoryBudgetTest {
    @Test fun arrayCapacityBudgetIncludesPrivateQueueAndConcurrentPrimaryReference() {
        val bytes = 3536L * 3536 * 2
        assertEquals(bytes * 8 + 32L * 1024 * 1024, AuxiliaryCaptureMemoryBudget.requiredHeapBytes(bytes, bytes))
        assertTrue(AuxiliaryCaptureMemoryBudget.requiredHeapBytes(Int.MAX_VALUE.toLong()) > Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { AuxiliaryCaptureMemoryBudget.requiredHeapBytes(0) }
    }
}
