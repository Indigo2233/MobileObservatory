package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FrameSnapshotRequestsTest {
    @Test fun pauseReleasesWaitingRequestBeforeItsCoroutineResumes() = runTest {
        val requests = FrameSnapshotRequests<Int>()
        val capture = async { requests.await("photo", 45_000) {} }
        runCurrent()
        assertEquals(FrameSnapshotRequests.State("photo", FrameSnapshotRequests.Phase.WAITING), requests.state)
        requests.cancel("paused")
        assertNull(requests.state)
        runCurrent()
        assertTrue(capture.isCancelled)
    }

    @Test fun queuedCapturesCannotReappearAfterPauseAndResume() = runTest {
        val requests = FrameSnapshotRequests<Int>()
        val first = async { requests.await("first", 45_000) {} }
        val queued = async { requests.await("queued", 45_000) {} }
        runCurrent()
        requests.cancel("paused")
        val resumed = async { requests.await("new", 45_000) {} }
        runCurrent()
        assertTrue(first.isCancelled)
        assertTrue(queued.isCancelled)
        assertEquals("new", requests.state?.owner)
        requests.fulfill({ true }) { 7 }
        assertEquals(7, resumed.await())
        assertNull(requests.state)
    }

    @Test fun copyingRequestRemainsCancellableAndDiscardsItsLateResult() = runTest {
        val requests = FrameSnapshotRequests<Int>()
        val capture = async { requests.await("photo", 45_000) {} }
        runCurrent()
        requests.fulfill({ true }) {
            assertEquals(FrameSnapshotRequests.Phase.COPYING, requests.state?.phase)
            requests.cancel("paused")
            assertNull(requests.state)
            3
        }
        runCurrent()
        assertTrue(capture.isCancelled)
        val next = async { requests.await("next", 45_000) {} }
        runCurrent()
        requests.fulfill({ true }) { 9 }
        assertEquals(9, next.await())
    }

    @Test fun pauseAfterDeliveryStillCancelsAnUnconsumedSnapshot() = runTest {
        val requests = FrameSnapshotRequests<Int>()
        val capture = async { requests.await("photo", 45_000) {} }
        runCurrent()
        requests.fulfill({ true }) { 3 }
        assertEquals(FrameSnapshotRequests.Phase.DELIVERED, requests.state?.phase)
        requests.cancel("paused")
        runCurrent()
        assertTrue(capture.isCancelled)
    }

    @Test fun oldAcquisitionCallbacksCannotSupplyTheNextCapture() = runTest {
        val requests = FrameSnapshotRequests<Int>()
        val capture = async { requests.await("photo", 45_000) {} }
        runCurrent()
        requests.fulfill({ false }) { fail("Must not copy an obsolete frame"); 0 }
        assertEquals(FrameSnapshotRequests.Phase.WAITING, requests.state?.phase)
        requests.fulfill({ true }) { 1 }
        assertEquals(1, capture.await())
    }

    @Test fun timeoutAndCallerCancellationBothReleaseTheSlot() = runTest {
        val requests = FrameSnapshotRequests<Int>()
        val timed = async { requests.await("timeout", 100) {} }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertNull(timed.await())
        assertNull(requests.state)
        val cancelled = async { requests.await("cancelled", 45_000) {} }
        runCurrent()
        cancelled.cancelAndJoin()
        assertNull(requests.state)
    }
}
