package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.Closeable
import java.util.ArrayDeque

/** Science-affecting settings. A changed key can never be mixed into a session. */
data class StackSessionKey(
    val camera: String,
    val width: Int,
    val height: Int,
    val format: PixelFormat,
    val roi: Roi,
    val hardwareBin: Int,
    val softwareBin: Int,
    val exposureUs: Double,
    val gain: Float,
    val filter: String,
    val readout: String,
    val offset: Float? = null,
    val pixelTraits: PixelTraits = PixelTraits(alignment = SampleAlignment.RIGHT)
)

enum class LiveStackPhase { EMPTY, RUNNING, PAUSING, PAUSED, RESET_REQUIRED, FAILED, CLOSED }

data class LiveStackState(
    val phase: LiveStackPhase = LiveStackPhase.EMPTY,
    val received: Long = 0,
    val accepted: Long = 0,
    val rejected: Long = 0,
    val dropped: Long = 0,
    val duplicates: Long = 0,
    val exposureSeconds: Double = 0.0,
    val stars: Int = 0,
    val rmsPixels: Double = 0.0,
    val rotationDegrees: Double = 0.0,
    val processingMs: Double = 0.0,
    val javaPeakBytes: Long = 0,
    val nativePeakBytes: Long = 0,
    val reason: String = "",
    val key: StackSessionKey? = null,
    val generation: Long = 0
)

/** Immutable owned snapshot: consumers must never mutate image/weight arrays. */
data class LiveStackSnapshot(
    val image: LinearImage,
    val weights: FloatArray,
    val state: LiveStackState,
    val details: Map<String, Any> = emptyMap()
)

data class LiveFrameOutcome(
    val accepted: Boolean,
    val stars: Int,
    val rmsPixels: Double,
    val rotationDegrees: Double,
    val reason: String = ""
)

/** Testable engine boundary. Called only by the single session worker. */
interface LiveStackEngine {
    fun add(image: LinearImage, exposureSeconds: Double, cancelled: () -> Boolean): LiveFrameOutcome
    fun snapshot(state: LiveStackState): LiveStackSnapshot?
}

/**
 * Takes a private byte copy BEFORE the camera gives its buffer to PreviewPipeline.
 * Two waiting inputs + one processing input; overflow drops the incoming input.
 * No bitmap, white balance, LP750 or preview stretch enters the science pipeline.
 * Reset changes an epoch, so an old in-flight result cannot repopulate a new stack.
 */
class LiveStackSession(
    scope: CoroutineScope,
    private val engineFactory: () -> LiveStackEngine,
    private val decode: (FrameData, StackSessionKey) -> LinearImage,
    private val onSnapshot: (LiveStackSnapshot?) -> Unit,
    private val nativeHeapBytes: () -> Long = { 0L },
    private val nowNanos: () -> Long = System::nanoTime,
    private val queueCapacity: Int = 2,
    private val maxInputBytes: Long = 32L * 1024 * 1024
) : Closeable {
    private data class Packet(val frame: FrameData, val key: StackSessionKey, val generation: Long)
    private val lock = Any()
    private val queue = ArrayDeque<Packet>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(LiveStackState())
    val state: StateFlow<LiveStackState> = _state.asStateFlow()
    @Volatile private var epoch = 0L
    @Volatile private var closed = false
    private var lastFrameId: Long? = null
    @Volatile var latestSnapshot: LiveStackSnapshot? = null
        private set
    private var workerEpoch = -1L
    private var engine: LiveStackEngine? = null
    private var lastPublish = 0L
    private val job: Job

    init {
        require(queueCapacity in 1..4)
        job = scope.launch {
            try { for (signal in wake) {
                while (!closed) {
                    val packet = synchronized(lock) {
                        if (workerEpoch != epoch) {
                            engine = null
                            workerEpoch = epoch
                        }
                        queue.pollFirst()
                    }
                    if (packet == null) {
                        finishPause()
                        break
                    }
                    process(packet)
                    // Free the invalidated engine before yielding to an offline job on the
                    // same dispatcher. Otherwise that job could retain both accumulators.
                    synchronized(lock) {
                        if (workerEpoch != epoch) { engine = null; workerEpoch = epoch }
                    }
                    // Give snapshot exports and lifecycle work a turn even at a saturated input FPS.
                    yield()
                }
            } } finally { engine = null }
        }
    }

    fun start(key: StackSessionKey) = synchronized(lock) {
        check(!closed)
        check(_state.value.phase == LiveStackPhase.EMPTY) { "Reset the previous live session first" }
        require(key.exposureUs.isFinite() && key.exposureUs > 0) { "Exposure must be known" }
        require(key.width > 0 && key.height > 0)
        lastFrameId = null
        _state.value = LiveStackState(phase = LiveStackPhase.RUNNING, key = key, generation = epoch)
        wake.trySend(Unit)
    }

    /** Returns immediately without retaining camera-owned bytes on every path. */
    fun offer(frame: FrameData, key: StackSessionKey) {
        synchronized(lock) {
            val old = _state.value
            if (old.phase != LiveStackPhase.RUNNING || closed) return
            if (key != old.key || frame.width != key.width || frame.height != key.height || frame.pixelFormat != key.format) {
                invalidateLocked("settings_changed")
                return
            }
            var next = old.copy(received = old.received + 1)
            if (lastFrameId?.let { frame.frameId <= it } == true) {
                _state.value = next.copy(duplicates = next.duplicates + 1)
                return
            }
            lastFrameId = frame.frameId
            val length = frame.width.toLong() * frame.height * frame.bytesPerPixel
            if (length !in 1..maxInputBytes || length > frame.data.size) {
                _state.value = next.copy(phase = LiveStackPhase.FAILED, reason = "input_budget")
                queue.clear()
                return
            }
            if (queue.size >= queueCapacity) {
                _state.value = next.copy(dropped = next.dropped + 1)
                return
            }
            try {
                val owned = frame.copy(data = frame.data.copyOf(length.toInt()))
                queue.addLast(Packet(owned, key, epoch))
                _state.value = next
            } catch (_: OutOfMemoryError) {
                _state.value = next.copy(phase = LiveStackPhase.FAILED, reason = "memory_budget")
                queue.clear()
            }
        }
        wake.trySend(Unit)
    }

    fun pause(reason: String = "") = synchronized(lock) {
        if (_state.value.phase == LiveStackPhase.RUNNING) {
            val queued = queue.size
            queue.clear()
            _state.value = _state.value.copy(
                phase = LiveStackPhase.PAUSING,
                dropped = _state.value.dropped + queued,
                reason = reason
            )
            wake.trySend(Unit)
        }
    }

    /** The SDK may restart its frame counter at one after capture stops. Call only after the
     * previous capture has stopped and before starting its replacement. Normal stack pause/
     * resume keeps the deduplication baseline; a capture restart preserves all accumulated data. */
    fun onCaptureRestarted() = synchronized(lock) {
        if (!closed && _state.value.phase in setOf(LiveStackPhase.PAUSING, LiveStackPhase.PAUSED)) {
            lastFrameId = null
        }
    }

    fun resume(key: StackSessionKey): Boolean = synchronized(lock) {
        if (_state.value.phase != LiveStackPhase.PAUSED) return false
        if (key != _state.value.key) {
            invalidateLocked("settings_changed")
            return false
        }
        _state.value = _state.value.copy(phase = LiveStackPhase.RUNNING, reason = "")
        true
    }

    /** Parameter writes and disconnect call this BEFORE changing the camera. */
    fun invalidate(reason: String = "settings_changed") = synchronized(lock) {
        if (_state.value.phase !in setOf(LiveStackPhase.EMPTY, LiveStackPhase.CLOSED)) invalidateLocked(reason)
    }

    private fun invalidateLocked(reason: String) {
        val discarded = queue.size
        queue.clear()
        _state.value = _state.value.copy(phase = LiveStackPhase.RESET_REQUIRED, reason = reason,
            dropped = _state.value.dropped + discarded)
        wake.trySend(Unit)
    }

    fun reset() {
        synchronized(lock) {
            epoch++
            queue.clear()
            lastFrameId = null
            latestSnapshot = null
            _state.value = LiveStackState(generation = epoch)
        }
        onSnapshot(null)
        wake.trySend(Unit)
    }

    private fun process(packet: Packet) {
        val started = nowNanos()
        try {
            val decoded = decode(packet.frame, packet.key)
            if (epoch != packet.generation || closed) return
            val currentEngine = engine ?: engineFactory().also { engine = it }
            val outcome = currentEngine.add(decoded, packet.key.exposureUs / 1_000_000.0) {
                closed || epoch != packet.generation
            }
            synchronized(lock) {
                if (epoch != packet.generation || closed) return
                val old = _state.value
                // Invalidation keeps the last completed snapshot; never publish an in-flight
                // image whose science settings may have been changing during its exposure.
                if (old.phase == LiveStackPhase.RESET_REQUIRED) return
                val javaBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                _state.value = old.copy(
                    accepted = old.accepted + if (outcome.accepted) 1 else 0,
                    rejected = old.rejected + if (outcome.accepted) 0 else 1,
                    exposureSeconds = old.exposureSeconds + if (outcome.accepted) packet.key.exposureUs / 1_000_000.0 else 0.0,
                    stars = outcome.stars,
                    rmsPixels = outcome.rmsPixels,
                    rotationDegrees = outcome.rotationDegrees,
                    processingMs = (nowNanos() - started) / 1_000_000.0,
                    javaPeakBytes = maxOf(old.javaPeakBytes, javaBytes),
                    nativePeakBytes = maxOf(old.nativePeakBytes, nativeHeapBytes()),
                    reason = outcome.reason
                )
            }
            if (nowNanos() - lastPublish >= 1_000_000_000L || latestSnapshot == null) publish(packet.generation)
        } catch (_: java.util.concurrent.CancellationException) {
            // Reset/close discards this generation and its engine.
        } catch (error: Exception) {
            fail(packet.generation, error.message ?: "processing_failed")
        } catch (_: OutOfMemoryError) {
            fail(packet.generation, "memory_budget")
        }
    }

    private fun fail(generation: Long, reason: String) = synchronized(lock) {
        if (epoch != generation || closed) return@synchronized
        queue.clear()
        _state.value = _state.value.copy(phase = LiveStackPhase.FAILED, reason = reason)
    }

    private fun publish(generation: Long) {
        val snapshotState = _state.value
        if (generation != epoch || snapshotState.phase == LiveStackPhase.RESET_REQUIRED) return
        val snapshot = engine?.snapshot(snapshotState) ?: return
        synchronized(lock) {
            if (generation != epoch || closed || _state.value.phase == LiveStackPhase.RESET_REQUIRED) return
            latestSnapshot = snapshot
            lastPublish = nowNanos()
        }
        onSnapshot(snapshot)
    }

    private fun finishPause() {
        if (_state.value.phase != LiveStackPhase.PAUSING) return
        publish(epoch)
        synchronized(lock) {
            if (_state.value.phase == LiveStackPhase.PAUSING) _state.value = _state.value.copy(phase = LiveStackPhase.PAUSED)
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            epoch++
            queue.clear()
            latestSnapshot = null
            _state.value = _state.value.copy(phase = LiveStackPhase.CLOSED)
        }
        wake.close()
        job.cancel()
        onSnapshot(null)
    }

    internal suspend fun awaitClosed() { job.join() }
}
