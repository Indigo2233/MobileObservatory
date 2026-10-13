package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.stacking.PixelTraits
import kotlinx.coroutines.channels.Channel

data class AuxiliaryRawSample(val frame: FrameData, val traits: PixelTraits?, val startElapsedMs: Long,
    val endElapsedMs: Long, val startedUtcMs: Long, val exposureDurationMs: Long,
    val earliestExposureElapsedMs: Long = startElapsedMs) {
    // Delivery cadence is not an exposure timestamp: an SDK can drain buffered frames quickly.
    val stamp get(): ObservationStamp {
        val midpoint = earliestExposureElapsedMs + (endElapsedMs - earliestExposureElapsedMs) / 2
        return ObservationStamp(startedUtcMs + midpoint - startElapsedMs, midpoint, exposureDurationMs,
            (endElapsedMs - earliestExposureElapsedMs + 1) / 2)
    }
}

/** Called before preview takes the SDK buffer. A slow consumer fails; it never hides a missing frame. */
class AuxiliaryFrameCollector(private val startElapsedMs: Long, private val startUtcMs: Long,
    private val exposureMs: Long, private val maximumFrameBytes: Long = Int.MAX_VALUE.toLong()) {
    private val samples = Channel<AuxiliaryRawSample>(1)
    private var previousElapsedMs = startElapsedMs
    private var generation: Long? = null
    private var previousId: Long? = null
    @Synchronized fun accept(frame: FrameData, traits: PixelTraits?, elapsedMs: Long, utcMs: Long) {
        if (samples.isClosedForSend) return
        if (frame.data.size.toLong() > maximumFrameBytes) {
            fail(IllegalStateException("Auxiliary frame exceeds the reserved memory budget")); return
        }
        val frameGeneration = frame.captureGeneration
        if (elapsedMs <= previousElapsedMs || elapsedMs - startElapsedMs < exposureMs ||
            frameGeneration == null || (generation != null && generation != frameGeneration) ||
            previousId?.let { frame.frameId <= it } == true ||
            kotlin.math.abs((utcMs - elapsedMs) - (startUtcMs - startElapsedMs)) > 250) {
            fail(IllegalStateException("Auxiliary capture generation or clock changed")); return
        }
        generation = frameGeneration; previousId = frame.frameId
        val result = AuxiliaryRawSample(frame.copy(data = frame.data.copyOf()), traits, previousElapsedMs,
            elapsedMs, startUtcMs + previousElapsedMs - startElapsedMs, exposureMs, startElapsedMs)
        previousElapsedMs = elapsedMs
        if (!samples.trySend(result).isSuccess) fail(IllegalStateException("Auxiliary sampling fell behind; retry"))
    }
    suspend fun receive(): AuxiliaryRawSample = samples.receive()
    fun fail(error: Throwable = IllegalStateException("Auxiliary capture stopped")) { samples.close(error) }
    fun close() { samples.cancel() }
}
