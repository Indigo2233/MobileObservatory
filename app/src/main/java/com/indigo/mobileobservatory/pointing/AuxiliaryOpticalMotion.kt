package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.ObserverSite
import kotlin.math.hypot

data class AuxiliaryOpticalMotionState(val epoch: Long = 0, val phase: MotionPhase = MotionPhase.SETTLING,
    val observedElapsedMs: Long? = null, val reliable: Boolean = false, val measuredResidualPx: Double? = null)

/** Camera pixels observe the rigid auxiliary installation. Missing data never counts as quiet. */
class AuxiliaryOpticalMotion(private val site: ObserverSite, private val trackingSky: Boolean) {
    var state = AuxiliaryOpticalMotionState(); private set
    private var layout = ""
    private var previous: OpticalFrameStability? = null
    private var anchor: OpticalStabilitySample? = null
    private var quietFrames = 0
    private var allowRestart = false
    private var knownScale: Double? = null

    @Synchronized fun expectAcquisitionRestart() { allowRestart = true }
    @Synchronized fun configure(sample: OpticalStabilitySample, certifiedQuiet: Boolean = false,
        observedElapsedMs: Long? = null) {
        sample.validate(); anchor = sample; knownScale = sample.observation.plateScaleDegPerPixel
        quietFrames = if (certifiedQuiet) 3 else 0
        if (certifiedQuiet) state = state.copy(phase = MotionPhase.STILL, reliable = true,
            observedElapsedMs = observedElapsedMs ?: state.observedElapsedMs)
    }
    @Synchronized fun invalidate() {
        state = AuxiliaryOpticalMotionState(state.epoch + 1, MotionPhase.SETTLING, state.observedElapsedMs)
        previous = null; anchor = null; quietFrames = 0
    }
    @Synchronized fun tick(nowElapsedMs: Long, maximumGapMs: Long): AuxiliaryOpticalMotionState {
        if (state.reliable && state.observedElapsedMs?.let { nowElapsedMs - it > maximumGapMs } == true) invalidate()
        return state
    }
    @Synchronized fun ingest(frame: OpticalFrameStability, layoutKey: String): AuxiliaryOpticalMotionState {
        if (runCatching { frame.validate() }.isFailure) { invalidate(); return state }
        if (layout.isNotEmpty() && layout != layoutKey) invalidate()
        layout = layoutKey
        val before = previous
        val controlledRestart = allowRestart && before?.captureGeneration != frame.captureGeneration
        if (before != null && !controlledRestart && (before.captureGeneration != frame.captureGeneration ||
            frame.frameId != before.frameId + 1 || frame.startElapsedMs != before.endElapsedMs)) invalidate()
        allowRestart = false
        previous = frame
        val reference = anchor
        val stars = reference?.stars ?: before?.stars
        if (stars == null) return state.copy(observedElapsedMs = frame.endElapsedMs).also { state = it }
        val used = HashSet<Int>()
        val distances = stars.mapNotNull { star ->
            val expected = if (reference != null) runCatching {
                OpticalRelativeMotion.predictedPixel(star, reference.observation, (frame.startElapsedMs + frame.endElapsedMs) / 2,
                    site, trackingSky)
            }.getOrNull() ?: return@mapNotNull null else star.x to star.y
            val index = frame.stars.indices.filterNot { it in used }.minByOrNull {
                hypot(frame.stars[it].x - expected.first, frame.stars[it].y - expected.second)
            } ?: return@mapNotNull null
            val current = frame.stars[index]
            val distance = hypot(current.x - expected.first, current.y - expected.second)
            if (distance > 60) return@mapNotNull null
            used += index; distance
        }
        if (distances.size < 5 || distances.size < minOf(stars.size, frame.stars.size) * .6) {
            invalidate(); previous = frame; return state
        }
        val residual = distances.maxOrNull()!!
        val intervalMs = frame.endElapsedMs - frame.startElapsedMs
        val threshold = if (reference != null) .75 else {
            // Initial/bootstrap quietness only schedules a capture; it cannot certify arrival.
            // The eventual WCS/PSF certificate measures a positive angular bound independently.
            val earthAllowance = if (trackingSky) 0.0 else knownScale?.let { intervalMs * .0042 / 1000 / it } ?: 0.0
            3 * frame.stars.maxOf { it.sigmaMajorPx } + 2 + earthAllowance
        }
        if (residual > threshold) {
            if (state.phase != MotionPhase.MOVING) state = state.copy(epoch = state.epoch + 1)
            quietFrames = 0; anchor = null
            state = state.copy(phase = MotionPhase.MOVING, observedElapsedMs = frame.endElapsedMs, reliable = true, measuredResidualPx = residual)
        } else {
            quietFrames++
            state = state.copy(phase = if (quietFrames >= 3) MotionPhase.STILL else MotionPhase.SETTLING,
                observedElapsedMs = frame.endElapsedMs, reliable = true, measuredResidualPx = residual)
        }
        return state
    }
}

data class AuxiliaryStableReference(val observation: OpticalObservation, val stability: OpticalPairStabilityEvidence)
