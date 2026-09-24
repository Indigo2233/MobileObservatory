package com.indigo.mobileobservatory.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceHistoryTest {
    @Test
    fun `undo and redo walk the recorded snapshots`() {
        val history = SequenceHistory(limit = 2)
        history.record("a")
        history.record("b")
        history.record("c")
        assertEquals("c", history.undo("d"))
        assertEquals("b", history.undo("c"))
        assertFalse(history.canUndo)
        assertEquals("c", history.redo("b"))
        assertEquals("d", history.redo("c"))
        assertFalse(history.canRedo)
        history.record("e")
        assertFalse(history.canRedo)
        assertTrue(history.canUndo)
    }
}
