package com.indigo.mobileobservatory.sequence

class SequenceHistory(private val limit: Int = 50) {
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun clear() {
        undo.clear()
        redo.clear()
    }

    fun record(before: String) {
        undo.addLast(before)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    fun undo(current: String): String? {
        if (undo.isEmpty()) return null
        val previous = undo.removeLast()
        redo.addLast(current)
        return previous
    }

    fun redo(current: String): String? {
        if (redo.isEmpty()) return null
        val next = redo.removeLast()
        undo.addLast(current)
        return next
    }
}
