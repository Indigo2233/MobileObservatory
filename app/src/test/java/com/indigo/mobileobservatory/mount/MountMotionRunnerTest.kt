package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MountMotionRunnerTest {
    @Test
    fun stopCancelsActiveMotionAndAbortsMountExactlyOnce() = runTest {
        var abortCount = 0
        val started = CompletableDeferred<Unit>()
        val runner = MountMotionRunner(this) { abortCount++ }

        assertTrue(
            runner.start(MountMotionState(MountMotionType.GOTO, "GOTO M42")) {
                started.complete(Unit)
                awaitCancellation()
            }
        )
        started.await()

        runner.stop()
        advanceUntilIdle()

        assertEquals(1, abortCount)
        assertFalse(runner.state.value.isActive)
    }

    @Test
    fun operationFailureAbortsMountAndReturnsToIdle() = runTest {
        var abortCount = 0
        var failure: Throwable? = null
        val runner = MountMotionRunner(this) { abortCount++ }

        runner.start(
            state = MountMotionState(MountMotionType.GOTO, "GOTO M31"),
            onError = { failure = it }
        ) {
            error("protocol failed")
        }
        advanceUntilIdle()

        assertEquals(1, abortCount)
        assertEquals("protocol failed", failure?.message)
        assertFalse(runner.state.value.isActive)
    }

    @Test
    fun normalCompletionDoesNotAbortMount() = runTest {
        var abortCount = 0
        val runner = MountMotionRunner(this) { abortCount++ }

        assertTrue(
            runner.start(MountMotionState(MountMotionType.GOTO, "GOTO M13")) {}
        )
        advanceUntilIdle()

        assertEquals(0, abortCount)
        assertFalse(runner.state.value.isActive)
    }

    @Test
    fun secondMotionCannotStartWhileOneIsActive() = runTest {
        val started = CompletableDeferred<Unit>()
        val runner = MountMotionRunner(this) {}

        assertTrue(
            runner.start(MountMotionState(MountMotionType.GOTO, "first")) {
                started.complete(Unit)
                awaitCancellation()
            }
        )
        started.await()

        assertFalse(
            runner.start(MountMotionState(MountMotionType.HOME, "second")) {}
        )
        runner.stop()
    }

    @Test
    fun manualMotionDoesNotShowTheGlobalStopPopup() {
        assertFalse(MountMotionState.Idle.showsGlobalStop)
        assertFalse(
            MountMotionState(MountMotionType.MANUAL, "Manual north").showsGlobalStop
        )
        assertFalse(MountMotionState(MountMotionType.GOTO, "GOTO M42").showsGlobalStop)
        assertTrue(
            MountMotionState(MountMotionType.GOTO, "GOTO M42", slewing = true).showsGlobalStop
        )
        assertTrue(
            MountMotionState(MountMotionType.GOTO, "Precision GOTO M42", holdStop = true)
                .showsGlobalStop
        )
        assertTrue(MountMotionState(MountMotionType.HOME, "Go home").showsGlobalStop)
        assertTrue(MountMotionState(MountMotionType.RA_MOVE, "RA move").showsGlobalStop)
    }

    @Test
    fun pendingAbortKeepsStoppingAndBlocksNextMotionEvenAfterOperationCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        val abortStarted = CompletableDeferred<Unit>()
        val releaseAbort = CompletableDeferred<Unit>()
        var aborts = 0
        val runner = MountMotionRunner(this) {
            aborts++; abortStarted.complete(Unit); releaseAbort.await()
        }
        assertTrue(runner.start(MountMotionState(MountMotionType.GOTO, "first", holdStop = true)) {
            started.complete(Unit); awaitCancellation()
        })
        started.await()
        val stop = async { runner.stop() }
        abortStarted.await(); runCurrent()
        assertTrue(runner.state.value.isStopping)
        assertTrue(runner.state.value.showsGlobalStop)
        assertFalse(stop.isCompleted)
        assertFalse(runner.start(MountMotionState(MountMotionType.HOME, "second")) {})
        releaseAbort.complete(Unit)
        assertTrue(stop.await())
        assertEquals(MountMotionState.Idle, runner.state.value)
        assertTrue(runner.start(MountMotionState(MountMotionType.HOME, "second")) {})
        advanceUntilIdle()
        assertEquals(1, aborts)
    }

    @Test
    fun concurrentStopsShareOneAbortAndCancelingTheElectedCallerDoesNotCancelIt() = runTest {
        val started = CompletableDeferred<Unit>()
        val abortStarted = CompletableDeferred<Unit>()
        val releaseAbort = CompletableDeferred<Unit>()
        var aborts = 0
        val runner = MountMotionRunner(this) {
            aborts++; abortStarted.complete(Unit); releaseAbort.await()
        }
        runner.start(MountMotionState(MountMotionType.GOTO, "first", holdStop = true)) {
            started.complete(Unit); awaitCancellation()
        }
        started.await()
        val first = async(start = CoroutineStart.UNDISPATCHED) { runner.stop() }
        abortStarted.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { runner.stop() }
        first.cancel(); runCurrent()
        assertTrue(runner.state.value.isStopping)
        assertFalse(second.isCompleted)
        assertEquals(1, aborts)
        releaseAbort.complete(Unit)
        first.join(); assertTrue(second.await())
        assertEquals(MountMotionState.Idle, runner.state.value)
        assertEquals(1, aborts)
    }

    @Test
    fun naturallyFinishingBodyCannotReleaseOwnershipBeforeAnElectedStopCompletes() = runTest {
        val started = CompletableDeferred<Unit>()
        val finishBody = CompletableDeferred<Unit>()
        val abortStarted = CompletableDeferred<Unit>()
        val releaseAbort = CompletableDeferred<Unit>()
        val runner = MountMotionRunner(this) {
            abortStarted.complete(Unit); releaseAbort.await()
        }
        runner.start(MountMotionState(MountMotionType.GOTO, "first", holdStop = true)) {
            withContext(NonCancellable) { started.complete(Unit); finishBody.await() }
        }
        started.await()
        val stop = async { runner.stop() }
        abortStarted.await()
        finishBody.complete(Unit); runCurrent()
        assertTrue(runner.state.value.isStopping)
        assertFalse(runner.start(MountMotionState(MountMotionType.HOME, "second")) {})
        releaseAbort.complete(Unit); assertTrue(stop.await())
        assertEquals(MountMotionState.Idle, runner.state.value)
    }

    @Test
    fun parentCancellationBeforeBodyStartsStillHoldsOwnershipUntilSafetyAbortFinishes() = runTest {
        val parent = SupervisorJob()
        val pendingScope = CoroutineScope(parent + StandardTestDispatcher(testScheduler))
        val abortStarted = CompletableDeferred<Unit>()
        val releaseAbort = CompletableDeferred<Unit>()
        var bodyRan = false
        val runner = MountMotionRunner(pendingScope) {
            abortStarted.complete(Unit); releaseAbort.await()
        }
        runner.start(MountMotionState(MountMotionType.GOTO, "first", holdStop = true)) { bodyRan = true }
        parent.cancel(); abortStarted.await(); runCurrent()
        assertFalse(bodyRan)
        assertTrue(runner.state.value.isStopping)
        assertFalse(runner.start(MountMotionState(MountMotionType.HOME, "second")) {})
        releaseAbort.complete(Unit); advanceUntilIdle()
        assertEquals(MountMotionState.Idle, runner.state.value)
    }
}
