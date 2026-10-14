package com.indigo.mobileobservatory.astrometry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Canceling a calibration/verification must release its native solver and acquisition lease. */
internal suspend fun awaitSolverProcess(process: Process, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    try {
        while (true) {
            currentCoroutineContext().ensureActive()
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) { process.destroyForcibly(); return@withContext false }
            if (process.waitFor(minOf(100L, remainingMs), TimeUnit.MILLISECONDS)) return@withContext true
        }
        @Suppress("UNREACHABLE_CODE") false
    } catch (cancel: CancellationException) {
        process.destroyForcibly()
        throw cancel
    }
}
