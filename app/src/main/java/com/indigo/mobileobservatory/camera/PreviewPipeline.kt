package com.indigo.mobileobservatory.camera

import android.graphics.Bitmap
import android.os.Debug
import com.indigo.mobileobservatory.stacking.PixelTraits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A rendered preview frame. Sequence forces StateFlow emission for a reused Bitmap. */
data class PreviewFrame(
    val bitmap: Bitmap,
    val sequence: Long
)

data class PreviewPerformanceStats(
    val receivedFrames: Long = 0,
    val renderedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val averageProcessingMs: Double = 0.0,
    val renderedFps: Double = 0.0,
    val nativeHeapBytes: Long = 0,
    val javaHeapBytes: Long = 0,
    val gcCount: Int = 0
)

/**
 * Owns preview backpressure, rendering cadence, and performance measurements.
 * Producers never wait for conversion: when rendering falls behind, only the newest frame survives.
 */
class PreviewPipeline internal constructor(
    private val scope: CoroutineScope,
    private val renderFrame: (FrameData) -> Bitmap,
    targetFps: Int,
    private val renderWithTraits: ((FrameData, PixelTraits?) -> Bitmap)? = null
) {
    constructor(scope: CoroutineScope, processor: FrameProcessor, targetFps: Int) :
        this(scope, { processor.frameToBitmap(it) }, targetFps, processor::frameToBitmap)

    private data class PreviewSample(val frame: FrameData, val traits: PixelTraits?)

    private val frameIntervalMs = (1_000L / targetFps.coerceIn(1, 60)).coerceAtLeast(1L)
    private val lifecycleLock = Any()
    // A cancelled waiting successor must not allow a third worker to overtake a still
    // converting predecessor. FrameProcessor owns reusable, non-thread-safe buffers.
    private val rendererMutex = Mutex()
    private val latestFrame = LatestFrameSlot<PreviewSample>()
    private val _frame = MutableStateFlow<PreviewFrame?>(null)
    private val _performance = MutableStateFlow(PreviewPerformanceStats())
    private var acceptingFrames = false
    private var generation = 0L
    private var recycleBuffer: (ByteArray) -> Unit = {}
    private var renderJob: Job? = null

    val frame: StateFlow<PreviewFrame?> = _frame.asStateFlow()
    val performance: StateFlow<PreviewPerformanceStats> = _performance.asStateFlow()

    fun submit(frame: FrameData, traits: PixelTraits? = null, expectedGeneration: Long? = null,
               rejectedRecycler: ((ByteArray) -> Unit)? = null) {
        val discarded: PreviewSample?
        val recycler: (ByteArray) -> Unit
        synchronized(lifecycleLock) {
            val sample = PreviewSample(frame, traits)
            val accepted = acceptingFrames && (expectedGeneration == null || expectedGeneration == generation)
            discarded = if (accepted) latestFrame.offer(sample) else sample
            recycler = if (accepted) recycleBuffer else rejectedRecycler ?: recycleBuffer
        }
        discarded?.let { releaseFrame(it.frame, recycler) }
    }

    fun start(
        paused: () -> Boolean = { false },
        onProcessed: (FrameData) -> Unit = {},
        recycleBuffer: (ByteArray) -> Unit = {},
        beforeRender: () -> Unit = {}
    ): Long = synchronized(lifecycleLock) {
        stopLocked(clearFrame = false)
        val predecessor = renderJob
        this.recycleBuffer = recycleBuffer
        acceptingFrames = true
        val sessionGeneration = generation
        val sessionRecycler = recycleBuffer
        renderJob = scope.launch(Dispatchers.Default) {
            predecessor?.join()
            rendererMutex.withLock {
                ensureActive()
                beforeRender()
                var sequence = _frame.value?.sequence ?: 0L
                var rendered = 0L
                var processingNanos = 0L
                var sampleRendered = 0L
                var sampleStartedNanos = System.nanoTime()
                while (isActive) {
                    val iterationStarted = System.nanoTime()
                    if (!paused()) {
                        val source = synchronized(lifecycleLock) {
                            if (isActive && generation == sessionGeneration) latestFrame.takeLatest() else null
                        }
                        if (source != null) {
                            try {
                                val processingStarted = System.nanoTime()
                                val bitmap = renderWithTraits?.invoke(source.frame, source.traits) ?: renderFrame(source.frame)
                                ensureActive()
                                processingNanos += System.nanoTime() - processingStarted
                                synchronized(lifecycleLock) {
                                    if (isActive && generation == sessionGeneration) {
                                        rendered++
                                        sampleRendered++
                                        sequence++
                                        _frame.value = PreviewFrame(bitmap, sequence)
                                        onProcessed(source.frame)
                                    }
                                }
                            } catch (oom: OutOfMemoryError) {
                                android.util.Log.e("PreviewPipeline", "OOM rendering ${source.frame.width}x${source.frame.height}", oom)
                            } finally {
                                releaseFrame(source.frame, sessionRecycler)
                            }
                        }
                    }

                    val now = System.nanoTime()
                    val sampleNanos = now - sampleStartedNanos
                    if (sampleNanos >= STATS_INTERVAL_NANOS) {
                        val stats = PreviewPerformanceStats(
                            receivedFrames = latestFrame.received,
                            renderedFrames = rendered,
                            droppedFrames = latestFrame.dropped,
                            averageProcessingMs = if (rendered == 0L) 0.0 else
                                processingNanos.toDouble() / rendered / 1_000_000.0,
                            renderedFps = sampleRendered * 1_000_000_000.0 / sampleNanos,
                            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
                            javaHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                            gcCount = Debug.getRuntimeStats()["art.gc.gc-count"]?.toIntOrNull() ?: 0
                        )
                        synchronized(lifecycleLock) {
                            if (isActive && generation == sessionGeneration) _performance.value = stats
                        }
                        sampleRendered = 0L
                        sampleStartedNanos = now
                    }

                    val elapsedMs = (System.nanoTime() - iterationStarted) / 1_000_000L
                    delay((frameIntervalMs - elapsedMs).coerceAtLeast(1L))
                }
            }
        }
        sessionGeneration
    }

    fun stop(clearFrame: Boolean = true) = synchronized(lifecycleLock) {
        stopLocked(clearFrame)
    }

    private fun stopLocked(clearFrame: Boolean) {
        generation++
        acceptingFrames = false
        renderJob?.cancel()
        // Keep the predecessor until its synchronous conversion has returned. stop()
        // remains non-blocking, while the next worker waits before reusing its buffers.
        latestFrame.clear()?.let { releaseFrame(it.frame, recycleBuffer) }
        recycleBuffer = {}
        if (clearFrame) _frame.value = null
    }

    fun close() = stop()

    private fun releaseFrame(frame: FrameData, recycler: (ByteArray) -> Unit) {
        try {
            recycler(frame.data)
        } catch (_: Throwable) {
            // Buffer recycling must never terminate capture or preview processing.
        }
    }

    private companion object {
        const val STATS_INTERVAL_NANOS = 1_000_000_000L
    }
}
