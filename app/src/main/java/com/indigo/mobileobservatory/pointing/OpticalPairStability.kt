package com.indigo.mobileobservatory.pointing

import kotlin.math.abs
import kotlin.math.hypot
import com.indigo.mobileobservatory.astro.ObserverSite

/** Raw-image evidence, in sensor pixels. Seeing and centroid error increase, never erase, motion. */
data class OpticalStarPsf(val x: Double, val y: Double, val sigmaMajorPx: Double,
    val sigmaMinorPx: Double, val snr: Double, val flux: Double) {
    fun validate() {
        require(listOf(x, y, sigmaMajorPx, sigmaMinorPx, snr, flux).all { it.isFinite() })
        require(sigmaMajorPx >= sigmaMinorPx && sigmaMinorPx > 0 && snr >= 5 && flux > 0)
    }
}

data class OpticalStabilitySample(val observation: OpticalObservation, val stars: List<OpticalStarPsf>,
    val captureStartElapsedMs: Long, val captureEndElapsedMs: Long, val captureGeneration: Long, val frameId: Long,
    val earliestExposureElapsedMs: Long = captureStartElapsedMs) {
    fun validate() {
        require(observation.kind == OpticalObservationKind.AUXILIARY_PLATE && observation.pose != null)
        require(observation.plateScaleDegPerPixel?.let { it.isFinite() && it > 0 } == true)
        require(captureGeneration > 0 && captureStartElapsedMs >= 0 && captureEndElapsedMs > captureStartElapsedMs)
        require(stars.size in 5..32); stars.forEach { it.validate() }
        require(earliestExposureElapsedMs in 0..captureStartElapsedMs)
        require(observation.stamp.elapsedMidpointMs - observation.stamp.clockUncertaintyMs >= earliestExposureElapsedMs - 1 &&
            observation.stamp.elapsedMidpointMs + observation.stamp.clockUncertaintyMs <= captureEndElapsedMs + 1)
    }
}

/** All raw frames in the acquisition interval are represented; three independently solved WCS anchor it. */
data class OpticalPairStabilityEvidence(val solvedSamples: List<OpticalStabilitySample>,
    val frameSamples: List<OpticalFrameStability>, val motionUpperBoundDeg: Double,
    val site: ObserverSite? = null, val trackingSky: Boolean = true) {
    fun validate(camera: OpticalObservation, reference: OpticalObservation) {
        require(solvedSamples.size == 3 && solvedSamples.map { it.observation.id }.toSet().size == 3)
        solvedSamples.forEach { it.validate() }
        require(solvedSamples.any { it.observation.id == camera.id })
        require(solvedSamples.all { it.observation.identity == camera.identity &&
            it.observation.geometry == camera.geometry && it.observation.acquisitionDeviceId == camera.acquisitionDeviceId })
        require(frameSamples.size in 3..64); frameSamples.forEach { it.validate() }
        solvedSamples.forEach { solved ->
            val raw = requireNotNull(frameSamples.firstOrNull { it.frameId == solved.frameId })
            require(raw.startElapsedMs == solved.captureStartElapsedMs && raw.endElapsedMs == solved.captureEndElapsedMs &&
                raw.earliestExposureElapsedMs == solved.earliestExposureElapsedMs &&
                raw.stars == solved.stars && raw.width == solved.observation.geometry?.width &&
                raw.height == solved.observation.geometry?.height) { "Solved samples do not match their raw evidence" }
        }
        require((solvedSamples.map { it.captureGeneration } + frameSamples.map { it.captureGeneration }).toSet().size == 1)
        require(frameSamples.map { it.frameId }.toSet().size == frameSamples.size)
        frameSamples.zipWithNext().forEach { (a, b) ->
            require(a.endElapsedMs == b.startElapsedMs && b.frameId == a.frameId + 1) { "Missing optical stability coverage" }
        }
        val start = minOf(camera.stamp.elapsedMidpointMs - camera.stamp.clockUncertaintyMs,
            reference.stamp.elapsedMidpointMs - reference.stamp.clockUncertaintyMs)
        val end = maxOf(camera.stamp.elapsedMidpointMs + camera.stamp.clockUncertaintyMs,
            reference.stamp.elapsedMidpointMs + reference.stamp.clockUncertaintyMs)
        require(frameSamples.first().earliestExposureElapsedMs <= start + 1 && frameSamples.last().endElapsedMs >= end - 1)
            { "Optical samples do not cover the complete reference interval" }
        val anchor = solvedSamples.first().observation
        val measured = OpticalPairStability.motionBound(frameSamples, requireNotNull(camera.plateScaleDegPerPixel), anchor, site, trackingSky)
        require(motionUpperBoundDeg.isFinite() && motionUpperBoundDeg in measured..0.05)
        // Independent astrometry must agree with the relative raw star evidence. An absolute
        // WCS error floor is not treated as precise motion, and neither is a claimed zero.
        solvedSamples.zipWithNext().forEach { (a, b) ->
            val timeMs = abs(a.observation.stamp.elapsedMidpointMs - b.observation.stamp.elapsedMidpointMs) +
                a.observation.stamp.clockUncertaintyMs + b.observation.stamp.clockUncertaintyMs
            val allowed = motionUpperBoundDeg + a.observation.uncertaintyDeg + b.observation.uncertaintyDeg + timeMs * .0042 / 1000
            require(a.observation.direction.vector().angleDeg(b.observation.direction.vector()) <= allowed)
                { "Independent auxiliary solves disagree" }
            require(requireNotNull(a.observation.pose).up.angleDeg(requireNotNull(b.observation.pose).up) <= allowed)
                { "Auxiliary image rotation changed" }
        }
    }
}

data class OpticalFrameStability(val frameId: Long, val startElapsedMs: Long, val endElapsedMs: Long,
    val exposureDurationMs: Long, val captureGeneration: Long, val width: Int, val height: Int,
    val stars: List<OpticalStarPsf>, val earliestExposureElapsedMs: Long = startElapsedMs) {
    val exposureMidpointMs get() = earliestExposureElapsedMs + (endElapsedMs - earliestExposureElapsedMs) / 2
    val timeUncertaintyMs get() = (endElapsedMs - earliestExposureElapsedMs + 1) / 2
    fun validate() {
        require(frameId >= 0 && startElapsedMs >= 0 && endElapsedMs > startElapsedMs && captureGeneration > 0)
        require(earliestExposureElapsedMs in 0..startElapsedMs)
        require(exposureDurationMs > 0 && endElapsedMs - startElapsedMs <= exposureDurationMs + 750)
            { "Auxiliary frames are delayed; retry after the camera settles" }
        require(width > 0 && height > 0 && stars.size in 5..32)
        stars.forEach { it.validate(); require(it.x in 0.0..width.toDouble() && it.y in 0.0..height.toDouble()) }
        // Stars must surround the reference pixel, rather than extrapolate rotation from one corner.
        require(stars.any { it.x < width / 2.0 } && stars.any { it.x > width / 2.0 } &&
            stars.any { it.y < height / 2.0 } && stars.any { it.y > height / 2.0 }) { "Need stars across the auxiliary image" }
    }
}

object OpticalPairStability {
    fun motionBound(frames: List<OpticalFrameStability>, scaleDegPerPixel: Double,
        anchor: OpticalObservation? = null, site: ObserverSite? = null, trackingSky: Boolean = true): Double {
        require(frames.size >= 3 && scaleDegPerPixel.isFinite() && scaleDegPerPixel > 0)
        frames.forEach { it.validate() }
        val first = frames.first()
        val thisAnchor = anchor
        var worstPx = 0.0
        frames.drop(1).forEach { next ->
            require(next.width == first.width && next.height == first.height)
            val used = HashSet<Int>()
            val distances = first.stars.mapNotNull { anchor ->
                val expected = if (trackingSky) anchor.x to anchor.y else OpticalRelativeMotion.predictedPixel(
                    anchor, requireNotNull(thisAnchor), next.exposureMidpointMs, site, false)
                val nearest = next.stars.indices.filterNot { it in used }.minByOrNull {
                    hypot(next.stars[it].x - expected.first, next.stars[it].y - expected.second)
                } ?: return@mapNotNull null
                val star = next.stars[nearest]
                val distance = hypot(star.x - expected.first, star.y - expected.second)
                if (distance > 60) return@mapNotNull null
                used += nearest
                // PSF widths cover motion during each exposure and a conservative centroid floor.
                distance + 4 * maxOf(anchor.sigmaMajorPx, star.sigmaMajorPx) + .5
            }
            require(distances.size >= 5 && distances.size >= minOf(first.stars.size, next.stars.size) * .6)
                { "Auxiliary stars cannot be matched reliably" }
            worstPx = maxOf(worstPx, distances.maxOrNull() ?: error("Missing stars"))
        }
        val timeError = if (trackingSky) 0.0 else (requireNotNull(anchor).stamp.clockUncertaintyMs +
            frames.maxOf { it.timeUncertaintyMs }) * .0042 / 1000
        return worstPx * scaleDegPerPixel + timeError
    }
}
