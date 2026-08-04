package com.indigo.mobileobservatory.camera

import android.graphics.Bitmap
import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Producers never block: when rendering falls behind, only the newest frame survives.
 */
class PreviewPipeline(
    private val scope: CoroutineScope,
    private val processor: FrameProcessor,
    targetFps: Int
) {
    private val frameIntervalMs = (1_000L / targetFps.coerceIn(1, 60)).coerceAtLeast(1L)
    private val latestFrame = LatestFrameSlot<FrameData>()
    private val _frame = MutableStateFlow<PreviewFrame?>(null)
    private val _performance = MutableStateFlow(PreviewPerformanceStats())
    private var renderJob: Job? = null

    val frame: StateFlow<PreviewFrame?> = _frame.asStateFlow()
    val performance: StateFlow<PreviewPerformanceStats> = _performance.asStateFlow()

    fun submit(frame: FrameData) {
        latestFrame.offer(frame)
    }

    fun start(
        paused: () -> Boolean = { false },
        onProcessed: (FrameData) -> Unit = {}
    ) {
        stop(clearFrame = false)
        renderJob = scope.launch(Dispatchers.Default) {
            var sequence = _frame.value?.sequence ?: 0L
            var rendered = 0L
            var processingNanos = 0L
            var sampleRendered = 0L
            var sampleStartedNanos = System.nanoTime()
            while (isActive) {
                val iterationStarted = System.nanoTime()
                if (!paused()) {
                    val source = latestFrame.takeLatest()
                    if (source != null) {
                        val processingStarted = System.nanoTime()
                        val bitmap = processor.frameToBitmap(source)
                        processingNanos += System.nanoTime() - processingStarted
                        rendered++
                        sampleRendered++
                        sequence++
                        _frame.value = PreviewFrame(bitmap, sequence)
                        onProcessed(source)
                    }
                }

                val now = System.nanoTime()
                val sampleNanos = now - sampleStartedNanos
                if (sampleNanos >= STATS_INTERVAL_NANOS) {
                    _performance.value = PreviewPerformanceStats(
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
                    sampleRendered = 0L
                    sampleStartedNanos = now
                }

                val elapsedMs = (System.nanoTime() - iterationStarted) / 1_000_000L
                delay((frameIntervalMs - elapsedMs).coerceAtLeast(1L))
            }
        }
    }

    fun stop(clearFrame: Boolean = true) {
        renderJob?.cancel()
        renderJob = null
        latestFrame.clear()
        if (clearFrame) _frame.value = null
    }

    fun close() = stop()

    private companion object {
        const val STATS_INTERVAL_NANOS = 1_000_000_000L
    }
}
