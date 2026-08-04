package com.indigo.mobileobservatory.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DeterministicResourceCleanerTest {
    @Test
    fun closeRunsEveryCleanupInOrderOnlyOnceEvenWhenOneFails() {
        val events = mutableListOf<String>()
        val cleaner = DeterministicResourceCleaner(
            { events += "jobs" },
            {
                events += "writer"
                error("writer close failed")
            },
            { events += "devices" },
            { events += "receiver" }
        )

        cleaner.close()
        cleaner.close()

        assertEquals(listOf("jobs", "writer", "devices", "receiver"), events)
    }
}
