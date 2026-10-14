package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    val isStopping: Boolean = false,
    /** Plain GOTO sets this only after the axes actually start moving. */
    val slewing: Boolean = false,
    /** Precision GOTO, home, and RA moves keep STOP even between slews. */
    val holdStop: Boolean = false,
    val manualDirection: MountDirection? = null,
    /** Internal owner for canceling one workflow without stopping a later, unrelated task. */
    val ownerToken: String? = null
) {
    val isActive: Boolean get() = type != MountMotionType.IDLE
    fun permitsManualRelease(direction: MountDirection?) = type == MountMotionType.MANUAL &&
        (direction == null || direction == manualDirection)

    /** Direction pads already have Stop; a global popup would reflow/cancel the press. */
    val showsGlobalStop: Boolean
        get() = when (type) {
            MountMotionType.IDLE, MountMotionType.MANUAL -> false
            MountMotionType.GOTO -> holdStop || slewing
            else -> isActive
        }

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
    private var operationOwner: MotionOperation? = null

    private class MotionOperation {
        lateinit var job: Job
        // Intentionally parentless: canceling a sender cannot cancel the shared stop promise.
        var abortCompletion: CompletableDeferred<Unit>? = null
    }
    private data class AbortRequest(val owner: MotionOperation,
        val completion: CompletableDeferred<Unit>, val sendsCommand: Boolean)

    fun start(
        state: MountMotionState,
        onError: (Throwable) -> Unit = {},
        operation: suspend () -> Unit
    ): Boolean {
        require(state.isActive) { "A mount motion cannot start in the idle state." }
        synchronized(lock) {
            // A canceled Job is immediately inactive, while its finally/abort can still be running.
            if (operationJob != null || scope.coroutineContext[Job]?.isActive == false) return false
            val owner = MotionOperation()
            _state.value = state
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    operation()
                } catch (_: CancellationException) {
                    safelyAbort(owner)
                } catch (error: Throwable) {
                    safelyAbort(owner)
                    onError(error)
                } finally {
                    // A stop may have elected another sender. Its command must finish before this job exits.
                    withContext(NonCancellable) {
                        synchronized(lock) { owner.abortCompletion }?.await()
                    }
                }
            }
            owner.job = job
            operationOwner = owner
            operationJob = job
            job.invokeOnCompletion { cause -> operationCompleted(owner, cause) }
            job.start()
            return true
        }
    }

    fun update(transform: (MountMotionState) -> MountMotionState) {
        synchronized(lock) {
            if (operationJob?.isActive != true || _state.value.isStopping) return
            _state.value = transform(_state.value)
        }
    }

    suspend fun stop(expectedOwnerToken: String? = null): Boolean {
        val request = synchronized(lock) {
            if (expectedOwnerToken != null && _state.value.ownerToken != expectedOwnerToken) return false
            operationOwner?.let(::requestAbortLocked)
        } ?: return false
        // Reserve the sender before canceling the operation. Even if this stop caller is canceled,
        // it must fulfill the promise the operation's finally is waiting on.
        withContext(NonCancellable) {
            request.owner.job.cancel()
            completeAbortOrAwait(request)
            request.owner.job.join()
            synchronized(lock) { clearCompletedLocked(request.owner) }
        }
        return true
    }

    private fun requestAbortLocked(owner: MotionOperation): AbortRequest? {
        if (operationOwner !== owner) return null
        _state.value = _state.value.copy(isStopping = true)
        owner.abortCompletion?.let { return AbortRequest(owner, it, false) }
        val completion = CompletableDeferred<Unit>()
        owner.abortCompletion = completion
        return AbortRequest(owner, completion, true)
    }

    private suspend fun safelyAbort(owner: MotionOperation) = withContext(NonCancellable) {
        val request = synchronized(lock) { requestAbortLocked(owner) } ?: return@withContext
        completeAbortOrAwait(request)
    }

    private suspend fun completeAbortOrAwait(request: AbortRequest) {
        if (request.sendsCommand) {
            try { runCatching { abortMotion() } }
            finally { request.completion.complete(Unit) }
        } else request.completion.await()
    }

    private fun clearCompletedLocked(owner: MotionOperation) {
        if (operationOwner !== owner) return
        operationOwner = null
        operationJob = null
        _state.value = MountMotionState.Idle
    }

    private fun operationCompleted(owner: MotionOperation, cause: Throwable?) {
        val pending = synchronized(lock) {
            if (operationOwner !== owner) return
            // Covers cancellation before the lazy coroutine body ever runs, and stop racing
            // after a normal finally has inspected the promise but before Job completion.
            val request = if (cause != null) requestAbortLocked(owner) else null
            val completion = request?.completion ?: owner.abortCompletion
            if (completion == null || completion.isCompleted) {
                clearCompletedLocked(owner)
                null
            } else request ?: AbortRequest(owner, completion, false)
        }
        if (pending != null) scope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            completeAbortOrAwait(pending)
            synchronized(lock) { clearCompletedLocked(owner) }
        }
    }
}
