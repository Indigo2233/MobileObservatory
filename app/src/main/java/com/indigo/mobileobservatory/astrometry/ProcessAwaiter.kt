package com.indigo.mobileobservatory.astrometry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Process ownership begins before IO dispatch, which can be skipped by pending cancellation. */
internal suspend fun awaitSolverProcess(process: Process, timeoutMs: Long): Boolean {
    var finished = false
    try {
        return withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                currentCoroutineContext().ensureActive()
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remainingMs <= 0) return@withContext false
                if (process.waitFor(minOf(100L, remainingMs), TimeUnit.MILLISECONDS)) {
                    finished = true
                    return@withContext true
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }
    } finally {
        if (!finished) process.destroyForcibly()
    }
}
