package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
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
}
