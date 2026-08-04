package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MountMotionType {
    IDLE,
    GOTO,
    HOME,
    RA_MOVE,
    MANUAL
}

data class MountMotionState(
    val type: MountMotionType = MountMotionType.IDLE,
    val label: String = "",
    val isStopping: Boolean = false
) {
    val isActive: Boolean get() = type != MountMotionType.IDLE

    companion object {
        val Idle = MountMotionState()
    }
}

/** Owns at most one mount motion and guarantees an abort on cancellation or failure. */
class MountMotionRunner(
    private val scope: CoroutineScope,
    private val abortMotion: suspend () -> Unit
) {
    private val lock = Any()
    private val _state = MutableStateFlow(MountMotionState.Idle)
    val state: StateFlow<MountMotionState> = _state.asStateFlow()

    private var operationJob: Job? = null
    private var abortSent = false

    fun start(
        state: MountMotionState,
        onError: (Throwable) -> Unit = {},
        operation: suspend () -> Unit
    ): Boolean {
        require(state.isActive) { "A mount motion cannot start in the idle state." }
        synchronized(lock) {
            if (operationJob?.isActive == true) return false
            abortSent = false
            _state.value = state
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    operation()
                } catch (_: CancellationException) {
                    safelyAbort(currentCoroutineContext()[Job])
                } catch (error: Throwable) {
                    safelyAbort(currentCoroutineContext()[Job])
                    onError(error)
                } finally {
                    val completedJob = currentCoroutineContext()[Job]
                    synchronized(lock) {
                        if (operationJob === completedJob) {
                            operationJob = null
                            _state.value = MountMotionState.Idle
                        }
                    }
                }
            }
            operationJob = job
            job.start()
            return true
        }
    }

    suspend fun stop(): Boolean {
        val job = synchronized(lock) {
            operationJob?.also {
                _state.value = _state.value.copy(isStopping = true)
            }
        } ?: return false
        job.cancel()
        safelyAbort(job)
        job.join()
        return true
    }

    private suspend fun safelyAbort(job: Job?) {
        val shouldAbort = synchronized(lock) {
            if (operationJob !== job || abortSent) {
                false
            } else {
                abortSent = true
                true
            }
        }
        if (!shouldAbort) return
        runCatching {
            withContext(NonCancellable) { abortMotion() }
        }
    }
}
