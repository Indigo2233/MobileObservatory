package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
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
        assertEquals(FrameSnapshotRequests.State("photo", FrameSnapshotRequests.Phase.WAITING), requests.currentState)
        requests.cancel("paused")
        assertNull(requests.currentState)
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
        assertEquals("new", requests.currentState?.owner)
        requests.fulfill({ true }) { 7 }
        assertEquals(7, resumed.await())
        assertNull(requests.currentState)
    }

    @Test fun copyingRequestRemainsCancellableAndDiscardsItsLateResult() = runTest {
        val requests = FrameSnapshotRequests<Int>()
        val capture = async { requests.await("photo", 45_000) {} }
        runCurrent()
        requests.fulfill({ true }) {
            assertEquals(FrameSnapshotRequests.Phase.COPYING, requests.currentState?.phase)
            requests.cancel("paused")
            assertNull(requests.currentState)
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
        assertEquals(FrameSnapshotRequests.Phase.DELIVERED, requests.currentState?.phase)
        requests.cancel("paused")
        runCurrent()
        assertTrue(capture.isCancelled)
    }

    @Test fun oldAcquisitionCallbacksCannotSupplyTheNextCapture() = runTest {
        val requests = FrameSnapshotRequests<Int>()
        val capture = async { requests.await("photo", 45_000) {} }
        runCurrent()
        requests.fulfill({ false }) { fail("Must not copy an obsolete frame"); 0 }
        assertEquals(FrameSnapshotRequests.Phase.WAITING, requests.currentState?.phase)
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
        assertNull(requests.currentState)
        val cancelled = async { requests.await("cancelled", 45_000) {} }
        runCurrent()
        cancelled.cancelAndJoin()
        assertNull(requests.currentState)
    }

    @Test fun aHeldSlotStaysVisibleWhileTheCallerWritesItsFile() = runTest {
        val requests = FrameSnapshotRequests<Int>()
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val save = async {
            requests.hold("photo_jpg", 45_000, {}, { true }) { snapshot ->
                // The slot belongs to the save for the whole write.
                assertEquals(FrameSnapshotRequests.State("photo_jpg", FrameSnapshotRequests.Phase.COPYING), requests.currentState)
                assertNull(snapshot)
                writing.complete(Unit)
                release.await()
            }
        }
        runCurrent()
        writing.await()
        assertEquals(FrameSnapshotRequests.State("photo_jpg", FrameSnapshotRequests.Phase.COPYING), requests.currentState)
        release.complete(Unit)
        save.await()
        assertNull(requests.currentState)
    }

    @Test fun aHeldSlotTakesNoLaterFrame() = runTest {
        val requests = FrameSnapshotRequests<Int>()
        val save = async {
            requests.hold("photo_jpg", 45_000, {}, { true }) {
                // A frame that arrives during the write belongs to nobody.
                requests.fulfill({ true }) { 5 }
            }
        }
        runCurrent()
        save.await()
        assertNull(requests.currentState)
        val next = async { requests.await("next", 45_000) {} }
        runCurrent()
        requests.fulfill({ true }) { 9 }
        assertEquals(9, next.await())
    }

    @Test fun aHoldWithoutAFrameWaitsForTheCamera() = runTest {
        val requests = FrameSnapshotRequests<Int>()
        val save = async {
            requests.hold("photo_fits", 45_000, {}, { false }) { snapshot ->
                assertEquals(7, snapshot)
            }
        }
        runCurrent()
        assertEquals(FrameSnapshotRequests.State("photo_fits", FrameSnapshotRequests.Phase.WAITING), requests.currentState)
        requests.fulfill({ true }) { 7 }
        save.await()
        assertNull(requests.currentState)
    }
}
