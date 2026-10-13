package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LiveStackSessionTest {
    private val key = StackSessionKey("camera-a", 2, 2, PixelFormat.MONO8, Roi(0, 0, 2, 2), 1, 1, 250_000.0, 20f, "L", "normal")
    private fun frame(id: Long, value: Int = 64) = FrameData(ByteArray(4) { value.toByte() }, 2, 2, PixelFormat.MONO8, id, id * 1_000)
    private fun decode(frame: FrameData, ignored: StackSessionKey) = LinearImage(2, 2, 1, FloatArray(4) { (frame.data[it].toInt() and 255) / 255f })

    private class Engine(private val beforeAdd: () -> Unit = {}) : LiveStackEngine {
        val samples = Collections.synchronizedList(mutableListOf<Float>())
        val exposures = Collections.synchronizedList(mutableListOf<Double>())
        var mean = 0f
        var snapshotCopies = 0
        override fun add(image: LinearImage, exposureSeconds: Double, cancelled: () -> Boolean): LiveFrameOutcome {
            beforeAdd()
            if (cancelled()) throw java.util.concurrent.CancellationException()
            samples += image.pixels[0]
            val accepted = image.pixels[0] > 0
            if (accepted) { exposures += exposureSeconds; mean = exposures.size.let { n -> mean + (image.pixels[0] - mean) / n } }
            return LiveFrameOutcome(accepted, if (accepted) 8 else 0, .11, .25, if (accepted) "" else "too_few_stars")
        }
        override fun snapshot(state: LiveStackState): LiveStackSnapshot? {
            if (exposures.isEmpty()) return null
            snapshotCopies++
            return LiveStackSnapshot(LinearImage(2, 2, 1, FloatArray(4) { mean }), FloatArray(4) { exposures.size.toFloat() }, state)
        }
    }

    @Test fun repeatedRejectionsShareSnapshotPixelsAndOnlyAcceptedFramesAdvanceImageVersion() = runTest {
        val engine = Engine()
        var time = 1_000_000_000L
        val session = LiveStackSession(this, { engine }, ::decode, {}, nowNanos = { time })
        try {
            session.start(key); session.offer(frame(1), key); runCurrent()
            val first = session.latestSnapshot!!
            repeat(10) { index ->
                time += 1_000_000_000L
                session.offer(frame(index + 2L, 0), key); runCurrent()
                assertSame(first.image, session.latestSnapshot!!.image)
                assertSame(first.weights, session.latestSnapshot!!.weights)
                assertEquals(index + 1L, session.latestSnapshot!!.state.rejected)
                assertEquals(1L, session.latestSnapshot!!.state.imageVersion)
            }
            session.pause(); runCurrent()
            assertEquals(1, engine.snapshotCopies)
            assertTrue(session.resume(key))
            time += 1_000_000_000L
            session.offer(frame(12, 128), key); runCurrent()
            assertEquals(2, engine.snapshotCopies)
            assertEquals(2L, session.latestSnapshot!!.state.imageVersion)
            assertNotSame(first.image, session.latestSnapshot!!.image)
            assertTrue(first.image.pixels.all { it == 64f / 255f })
        } finally { session.close(); runCurrent() }
    }

    @Test fun rejectedFramePublishesAnyAcceptedPixelsWaitingForThrottle() = runTest {
        var time = 1_000_000_000L
        val session = LiveStackSession(this, { Engine() }, ::decode, {}, nowNanos = { time })
        try {
            session.start(key); session.offer(frame(1), key); runCurrent()
            session.offer(frame(2, 128), key); runCurrent()
            assertEquals(1L, session.latestSnapshot!!.state.imageVersion)
            time += 1_000_000_000L
            session.offer(frame(3, 0), key); runCurrent()
            assertEquals(2L, session.latestSnapshot!!.state.imageVersion)
            assertEquals(1L, session.latestSnapshot!!.state.rejected)
            assertEquals(96f / 255f, session.latestSnapshot!!.image.pixels[0], 1e-6f)
        } finally { session.close(); runCurrent() }
    }

    @Test fun ownedCopySurvivesCameraBufferRecyclingAndQueueIsBounded() = runTest {
        val engine = Engine()
        val snapshots = mutableListOf<LiveStackSnapshot?>()
        val session = LiveStackSession(this, { engine }, ::decode, snapshots::add)
        try {
            session.start(key)
            val input = frame(1, 64)
            session.offer(input, key)
            input.data.fill(0) // PreviewPipeline or camera recycles original immediately after offer.
            session.offer(frame(2, 128), key)
            session.offer(frame(3, 255), key)
            assertEquals(3L, session.state.value.received)
            assertEquals(1L, session.state.value.dropped)
            runCurrent()
            assertEquals(listOf(64f / 255, 128f / 255), engine.samples)
            assertEquals(2L, session.state.value.accepted)
            assertEquals(.5, session.state.value.exposureSeconds, 0.0)
            session.pause(); runCurrent()
            assertEquals(LiveStackPhase.PAUSED, session.state.value.phase)
            assertEquals(96f / 255, session.latestSnapshot!!.image.pixels[0], 1e-6f)
            assertTrue(session.latestSnapshot!!.weights.all { it == 2f })
        } finally { session.close(); runCurrent() }
    }

    @Test fun pauseDropsWaitingInputsAndResumePreservesAcceptedData() = runTest {
        var engines = 0
        val engine = Engine()
        val session = LiveStackSession(this, { engines++; engine }, ::decode, {})
        try {
            session.start(key); session.offer(frame(1), key); runCurrent()
            session.offer(frame(2), key); session.offer(frame(3), key)
            session.pause("background"); runCurrent()
            assertEquals(LiveStackPhase.PAUSED, session.state.value.phase)
            assertEquals(2L, session.state.value.dropped)
            session.offer(frame(4), key); runCurrent()
            assertEquals(1L, session.state.value.accepted)
            assertTrue(session.resume(key))
            session.offer(frame(5), key); runCurrent()
            assertEquals(2L, session.state.value.accepted)
            assertEquals(1, engines)
            assertEquals(.5, session.state.value.exposureSeconds, 0.0)
        } finally { session.close(); runCurrent() }
    }

    @Test fun duplicatesAndQualityRejectsDoNotIncreaseExposure() = runTest {
        val engine = Engine()
        val session = LiveStackSession(this, { engine }, ::decode, {})
        try {
            session.start(key)
            session.offer(frame(42), key); session.offer(frame(42), key); runCurrent()
            session.offer(frame(43, 0), key); runCurrent()
            assertEquals(3L, session.state.value.received)
            assertEquals(1L, session.state.value.duplicates)
            assertEquals(1L, session.state.value.accepted)
            assertEquals(1L, session.state.value.rejected)
            assertEquals(.25, session.state.value.exposureSeconds, 0.0)
            assertEquals(listOf(.25), engine.exposures)
        } finally { session.close(); runCurrent() }
    }

    @Test fun captureRestartAcceptsResetSdkIdsWithoutResettingAccumulationOrGeneration() = runTest {
        for (finishPauseFirst in listOf(false, true)) {
            var engines = 0
            val engine = Engine()
            val session = LiveStackSession(this, { engines++; engine }, ::decode, {})
            try {
                session.start(key); session.offer(frame(1000, 64), key); runCurrent()
                val generation = session.state.value.generation
                session.pause("capture_paused")
                if (finishPauseFirst) runCurrent()
                assertEquals(if (finishPauseFirst) LiveStackPhase.PAUSED else LiveStackPhase.PAUSING,
                    session.state.value.phase)
                session.onCaptureRestarted()
                assertEquals(1L, session.state.value.accepted)
                assertEquals(.25, session.state.value.exposureSeconds, 0.0)
                assertEquals(generation, session.state.value.generation)
                runCurrent()
                assertTrue(session.resume(key))
                session.offer(frame(1, 192), key); runCurrent()
                session.offer(frame(1, 192), key); runCurrent()
                session.pause(); runCurrent()
                assertEquals(2L, session.state.value.accepted)
                assertEquals(1L, session.state.value.duplicates)
                assertEquals(.5, session.state.value.exposureSeconds, 0.0)
                assertEquals(generation, session.state.value.generation)
                assertEquals(1, engines)
                assertEquals(128f / 255, session.latestSnapshot!!.image.pixels[0], 1e-6f)
                assertTrue(session.latestSnapshot!!.weights.all { it == 2f })
            } finally { session.close(); runCurrent() }
        }
    }

    @Test fun ordinaryResumeStillRejectsDuplicateIdsAndRestartNoticeOutsidePauseIsIgnored() = runTest {
        val session = LiveStackSession(this, { Engine() }, ::decode, {})
        try {
            session.start(key); session.offer(frame(1000), key); runCurrent()
            session.onCaptureRestarted() // A running session cannot discard duplicate protection.
            session.offer(frame(1000), key); runCurrent()
            session.pause(); runCurrent()
            assertTrue(session.resume(key))
            session.offer(frame(1000), key); session.offer(frame(999), key); runCurrent()
            assertEquals(1L, session.state.value.accepted)
            assertEquals(3L, session.state.value.duplicates)
            assertEquals(.25, session.state.value.exposureSeconds, 0.0)
            session.offer(frame(1001), key); runCurrent()
            assertEquals(2L, session.state.value.accepted)
            session.close()
            val closed = session.state.value
            session.onCaptureRestarted()
            assertEquals(closed, session.state.value)
            assertFalse(session.resume(key))
        } finally { session.close(); runCurrent() }
    }

    @Test fun everyScienceSettingRequiresNewSessionAndCannotResumeOldOne() = runTest {
        val changes = listOf(
            key.copy(camera = "camera-b"), key.copy(exposureUs = 500_000.0), key.copy(gain = 21f),
            key.copy(roi = Roi(1, 0, 2, 2)), key.copy(hardwareBin = 2), key.copy(softwareBin = 2),
            key.copy(format = PixelFormat.MONO16), key.copy(filter = "R"), key.copy(readout = "fast"), key.copy(offset = 2f),
            key.copy(demosaicAlgorithm = DemosaicAlgorithm.BILINEAR), key.copy(demosaicAlgorithm = DemosaicAlgorithm.VNG)
        )
        for (changed in changes) {
            val session = LiveStackSession(this, { Engine() }, ::decode, {})
            try {
                session.start(key); session.offer(frame(1), key); runCurrent()
                val saved = session.latestSnapshot
                session.offer(frame(2), changed); runCurrent()
                assertEquals(changed.toString(), LiveStackPhase.RESET_REQUIRED, session.state.value.phase)
                assertEquals("settings_changed", session.state.value.reason)
                assertSame(saved, session.latestSnapshot)
                assertFalse(session.resume(changed))
                session.reset(); session.start(changed)
                assertEquals(changed, session.state.value.key)
                assertEquals(0L, session.state.value.accepted)
            } finally { session.close(); runCurrent() }
        }
    }

    @Test fun rawRecordingIdentityIgnoresOnlyDemosaicChoice() {
        for (algorithm in DemosaicAlgorithm.values()) {
            val selected = key.copy(demosaicAlgorithm = algorithm)
            assertEquals(key.rawRecordingKey(), selected.rawRecordingKey())
            assertNotEquals(selected.rawRecordingKey(), selected.copy(gain = key.gain + 1).rawRecordingKey())
            assertNotEquals(selected.rawRecordingKey(), selected.copy(roi = Roi(1, 0, 2, 2)).rawRecordingKey())
            assertNotEquals(selected.rawRecordingKey(), selected.copy(format = PixelFormat.BAYER_RG16).rawRecordingKey())
        }
    }

    @Test fun resetClearsSnapshotCountersAndConstructsFreshEngine() = runTest {
        var engines = 0
        val notifications = mutableListOf<LiveStackSnapshot?>()
        val session = LiveStackSession(this, { engines++; Engine() }, ::decode, notifications::add)
        try {
            session.start(key); session.offer(frame(7, 32), key); runCurrent()
            assertNotNull(session.latestSnapshot)
            val oldEpoch = session.state.value.generation
            session.reset(); runCurrent()
            assertEquals(LiveStackPhase.EMPTY, session.state.value.phase)
            assertNull(session.latestSnapshot)
            assertNull(notifications.last())
            assertTrue(session.state.value.generation > oldEpoch)
            session.start(key); session.offer(frame(7, 192), key); runCurrent()
            assertEquals(2, engines)
            assertEquals(1L, session.state.value.accepted)
            assertEquals(192f / 255, session.latestSnapshot!!.image.pixels[0], 0f)
        } finally { session.close(); runCurrent() }
    }

    @Test fun disconnectFreezesLastCompletedSnapshotAndDropsPendingData() = runTest {
        val session = LiveStackSession(this, { Engine() }, ::decode, {})
        try {
            session.start(key); session.offer(frame(1), key); runCurrent()
            val saved = session.latestSnapshot
            session.offer(frame(2), key)
            session.invalidate("camera_disconnected"); runCurrent()
            session.offer(frame(3), key); runCurrent()
            assertEquals(LiveStackPhase.RESET_REQUIRED, session.state.value.phase)
            assertEquals("camera_disconnected", session.state.value.reason)
            assertEquals(1L, session.state.value.accepted)
            assertSame(saved, session.latestSnapshot)
        } finally { session.close(); runCurrent() }
    }

    @Test fun resetDuringBlockedDecodeCannotPublishStaleGeneration() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val snapshots = Collections.synchronizedList(mutableListOf<LiveStackSnapshot?>())
        val session = LiveStackSession(scope, { Engine() }, { packet, settings ->
            if (packet.frameId == 1L) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            decode(packet, settings)
        }, snapshots::add)
        try {
            session.start(key); session.offer(frame(1, 32), key)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            session.reset(); session.start(key); session.offer(frame(2, 192), key)
            release.countDown()
            withTimeout(5_000) { while (session.latestSnapshot == null) delay(5) }
            assertEquals(1L, session.state.value.accepted)
            assertEquals(192f / 255, session.latestSnapshot!!.image.pixels[0], 0f)
            assertTrue(snapshots.filterNotNull().all { it.image.pixels[0] == 192f / 255 })
        } finally { release.countDown(); session.close(); scope.cancel() }
    }

    @Test fun resetDuringBlockedEngineAddCancelsOldEngineAndCloseClearsResources() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var engineNumber = 0
        val session = LiveStackSession(scope, {
            engineNumber++
            if (engineNumber == 1) Engine { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } else Engine()
        }, ::decode, {})
        try {
            session.start(key); session.offer(frame(1), key)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            session.reset(); session.start(key); session.offer(frame(2, 200), key)
            release.countDown()
            withTimeout(5_000) { while (session.latestSnapshot == null) delay(5) }
            assertEquals(2, engineNumber)
            assertEquals(1L, session.state.value.accepted)
            assertEquals(200f / 255, session.latestSnapshot!!.image.pixels[0], 0f)
            session.close()
            assertEquals(LiveStackPhase.CLOSED, session.state.value.phase)
            assertNull(session.latestSnapshot)
            session.offer(frame(3), key)
            assertEquals(1L, session.state.value.accepted)
        } finally { release.countDown(); session.close(); scope.cancel() }
    }

    @Test fun oversizedOrTruncatedInputFailsBeforeDecodeAndProvidesRecoveryReason() = runTest {
        var decodes = 0
        val session = LiveStackSession(this, { Engine() }, { f, k -> decodes++; decode(f, k) }, {})
        try {
            session.start(key)
            session.offer(frame(1).copy(data = byteArrayOf(1)), key); runCurrent()
            assertEquals(LiveStackPhase.FAILED, session.state.value.phase)
            assertEquals("input_budget", session.state.value.reason)
            assertEquals(0, decodes)
            session.reset(); session.start(key); session.offer(frame(2), key); runCurrent()
            assertEquals(1L, session.state.value.accepted)
        } finally { session.close(); runCurrent() }
    }
}
