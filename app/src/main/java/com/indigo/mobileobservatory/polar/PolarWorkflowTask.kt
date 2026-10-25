package com.indigo.mobileobservatory.polar

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The running workflow owns its capture, solve and future motion until cancellation completes. */
class PolarWorkflowTask(private val scope: CoroutineScope, private val stopMotion: () -> Unit) {
    private val lock = Any()
    private var job: Job? = null
    private var ownsMotion = false
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun start(stopMotionOnFinish: Boolean = true, block: suspend () -> Unit): Boolean {
        val next = synchronized(lock) {
            if (job?.isCompleted == false) return false
            scope.launch(start = CoroutineStart.LAZY) {
                try { block() } finally { if (stopMotionOnFinish) stopMotion() }
            }.also { job = it; ownsMotion = stopMotionOnFinish; _busy.value = true }
        }
        next.invokeOnCompletion { synchronized(lock) { if (job === next) _busy.value = false } }
        next.start()
        return true
    }

    fun stop() = synchronized(lock) {
        if (job?.isCompleted == false) { job?.cancel(); if (ownsMotion) stopMotion() }
    }
}
