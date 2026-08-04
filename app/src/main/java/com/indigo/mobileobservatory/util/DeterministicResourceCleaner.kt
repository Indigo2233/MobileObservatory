package com.indigo.mobileobservatory.util

import java.util.concurrent.atomic.AtomicBoolean

/** Runs best-effort cleanup steps in order and guarantees they run at most once. */
class DeterministicResourceCleaner(
    private vararg val cleanupSteps: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cleanupSteps.forEach { step ->
            runCatching(step)
        }
    }
}
