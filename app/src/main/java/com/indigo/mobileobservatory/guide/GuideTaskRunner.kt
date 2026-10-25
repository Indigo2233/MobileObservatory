package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.mount.MountDirection
import kotlinx.coroutines.*

/** One calibration/correction task owns mount pulses until physical stop cleanup has finished. */
class GuideTaskRunner(
    private val scope: CoroutineScope,
    private val startPulse: suspend (MountDirection, Int) -> Unit,
    private val stopPulse: suspend (MountDirection) -> Unit
) {
    private val lock = Any()
    private var job: Job? = null
    val busy: Boolean get() = synchronized(lock) { job?.isCompleted == false }

    fun launch(block: suspend GuideTaskRunner.() -> Unit): Boolean {
        val next = synchronized(lock) {
            if (job?.isCompleted == false) return false
            scope.launch(start = CoroutineStart.LAZY) { block() }.also { job = it }
        }
        next.start()
        return true
    }
    fun cancel() { synchronized(lock) { job?.cancel() } }
    suspend fun pulse(direction: MountDirection, durationMs: Int) {
        require(durationMs in 1..5000)
        currentCoroutineContext().ensureActive()
        try {
            startPulse(direction, durationMs)
            currentCoroutineContext().ensureActive()
            delay(durationMs.toLong())
        } finally {
            withContext(NonCancellable) { stopPulse(direction) }
        }
    }
}
