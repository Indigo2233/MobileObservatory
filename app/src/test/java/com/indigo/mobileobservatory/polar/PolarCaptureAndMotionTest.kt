package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astrometry.*
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PolarCaptureAndMotionTest {
    @Test fun preMoveExposuresAreSkippedBeforeASampleIsAccepted(): Unit = runTest {
        var reads = 0
        val result = awaitFreshPlateSolveFrame(5_000_000f, 10_000, { currentTime }, {}, {
            delay(it)
            reads++
            FrameData(ByteArray(16), 4, 4, PixelFormat.MONO8, reads.toLong(), if (reads == 1) 14_900 else 15_000)
        })
        assertEquals(2, reads); assertEquals(15_000L, result!!.timestamp)
        assertEquals(45_000L, plateSolveFrameWaitMs(5_000_000f))
        assertEquals(195_000L, plateSolveFrameWaitMs(60_000_000f))
    }

    @Test fun disconnectedCameraFailsPromptlyWithoutAFullTimeout(): Unit = runTest {
        var connected = true
        try {
            awaitFreshPlateSolveFrame(5_000_000f, 0, { currentTime }, { check(connected) { "USB disconnected" } }, {
                delay(it); connected = false; null
            })
            fail("Disconnect ignored")
        } catch (failure: IllegalStateException) { assertEquals("USB disconnected", failure.message) }
        assertEquals(1000L, currentTime)
    }

    @Test fun noImageReturnsARealTimeoutAndCancelledWaitCannotContinue(): Unit = runTest {
        assertNull(awaitFreshPlateSolveFrame(5_000_000f, 0, { currentTime }, {}, { delay(it); null }))
        assertEquals(45_000L, currentTime)
        var subsequent = false
        val task = launch { awaitFreshPlateSolveFrame(5_000_000f, 0, { currentTime }, {}, { delay(it); null }); subsequent = true }
        runCurrent(); task.cancelAndJoin(); assertFalse(subsequent)
    }

    @Test fun aMoveThatNeverStartsOrNeverEndsCannotBeReportedComplete(): Unit = runTest {
        assertEquals(PolarMoveOutcome.NOT_STARTED, awaitPolarMove({ false }, 10.0, 3.0))
        assertEquals(5000L, currentTime)
        assertEquals(PolarMoveOutcome.TIMED_OUT, awaitPolarMove({ true }, 10.0, 3.0))
        assertEquals(25_000L, currentTime)
    }

    @Test fun completionWaitsForTheActualBusyTransition(): Unit = runTest {
        val begin = currentTime
        assertEquals(PolarMoveOutcome.COMPLETE, awaitPolarMove({ currentTime - begin in 100..2099 }, 10.0, 3.0))
        assertTrue(currentTime >= 2100)
    }
}
