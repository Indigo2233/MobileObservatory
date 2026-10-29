package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.mount.MountDirection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

data class GuideCalibrationConfig(val steps: Int = 7, val distancePx: Float = 15f, val initialPulseMs: Int = 1200) {
    init { require(steps in 5..20 && distancePx in 10f..50f && initialPulseMs in 50..5000) }
}
enum class GuideCalibrationPhase { CLEAR_EAST, EAST, WEST, CLEAR_NORTH, NORTH, SOUTH }
data class GuideCalibrationProgress(val phase: GuideCalibrationPhase, val step: Int, val distancePx: Float)
data class GuideCalibrationResult(val calibration: GuideCalibration, val stars: List<GuideStar>)

/** Measures incremental shifts, regresses cumulative positions, and excludes take-up from the fit. */
class GuideCalibrator(
    private val config: GuideCalibrationConfig,
    private val pulse: suspend (MountDirection, Int) -> Unit,
    private val measure: suspend () -> List<GuideStar>,
    private val progress: (GuideCalibrationProgress) -> Unit = {}
) {
    private val module = GuideModule()
    private var stars = emptyList<GuideStar>()
    private data class Sample(val ms: Int, val x: Float, val y: Float)
    private data class Axis(val x: Float, val y: Float, val steps: Int, val travel: Float, val backlash: Int, val residual: Float)

    suspend fun run(): GuideCalibrationResult {
        stars = measure(); check(stars.isNotEmpty()) { "No usable calibration stars" }
        val east = axis(MountDirection.EAST, MountDirection.WEST, GuideCalibrationPhase.CLEAR_EAST, GuideCalibrationPhase.EAST, GuideCalibrationPhase.WEST)
        val north = axis(MountDirection.NORTH, MountDirection.SOUTH, GuideCalibrationPhase.CLEAR_NORTH, GuideCalibrationPhase.NORTH, GuideCalibrationPhase.SOUTH)
        val calibration = GuideCalibration(east.x, east.y, north.x, north.y, config.initialPulseMs,
            east.steps, north.steps, east.travel, north.travel, east.backlash, north.backlash, max(east.residual, north.residual))
        check(module.projectError(calibration, 0f, 0f) != null) { "Calibration axes are degenerate" }
        return GuideCalibrationResult(calibration, stars)
    }

    private suspend fun move(direction: MountDirection, ms: Int): GuideVector {
        currentCoroutineContext().ensureActive()
        pulse(direction, ms)
        val next = measure()
        val shift = module.matchShift(stars, next) ?: error("Calibration star lost")
        stars = next
        return shift
    }

    private suspend fun axis(forward: MountDirection, reverse: MountDirection, clearPhase: GuideCalibrationPhase,
                             outPhase: GuideCalibrationPhase, backPhase: GuideCalibrationPhase): Axis {
        val clearingRate = clearBacklash(forward, clearPhase)
        val samples = mutableListOf(Sample(0, 0f, 0f))
        var x = 0f; var y = 0f; var totalMs = 0
        var stepMs = ceil(config.distancePx / config.steps / clearingRate).toInt().coerceIn(50, config.initialPulseMs)
        for (i in 1..60) {
            val shift = move(forward, stepMs)
            x += shift.x; y += shift.y; totalMs += stepMs
            samples += Sample(totalMs, x, y)
            val distance = hypot(x, y)
            progress(GuideCalibrationProgress(outPhase, i, distance))
            if (i >= config.steps && distance >= config.distancePx) break
            // Use measured response to approach the requested travel in the requested step count.
            if (distance > .5f) stepMs = ceil(config.distancePx / config.steps * totalMs / distance).toInt().coerceIn(50, 5000)
        }
        val travel = hypot(x, y)
        check(samples.size > config.steps && travel >= config.distancePx) { "Insufficient calibration travel" }
        val meanT = samples.map { it.ms.toDouble() }.average()
        val meanX = samples.map { it.x.toDouble() }.average(); val meanY = samples.map { it.y.toDouble() }.average()
        val variance = samples.sumOf { (it.ms - meanT).pow(2) }
        val vx = (samples.sumOf { (it.ms - meanT) * (it.x - meanX) } / variance).toFloat()
        val vy = (samples.sumOf { (it.ms - meanT) * (it.y - meanY) } / variance).toFloat()
        val residual = sqrt(samples.sumOf {
            (it.x - meanX - vx * (it.ms - meanT)).pow(2) + (it.y - meanY - vy * (it.ms - meanT)).pow(2)
        } / samples.size).toFloat()
        check(residual <= max(.75f, travel * .1f)) { "Calibration fit is inconsistent" }
        val rate = hypot(vx, vy); check(rate > 0f && rate.isFinite()) { "Invalid calibration rate" }
        val ux = vx / rate; val uy = vy / rate
        var returned = 0f; var commanded = 0; var backlash = 0
        var returnX = 0f; var returnY = 0f
        val returnRates = mutableListOf<Float>()
        var moving = 0; var reached = false
        for (i in 1..80) {
            val remaining = travel - returned
            val ms = min(stepMs, ceil(remaining / rate).toInt().coerceAtLeast(50)).coerceIn(50, 5000)
            val shift = move(reverse, ms)
            returnX += shift.x; returnY += shift.y
            commanded += ms
            val projected = -(shift.x * ux + shift.y * uy)
            returned += projected
            if (projected >= rate * ms * .65f) {
                moving++
                returnRates += projected / ms
                if (moving <= 3) backlash = (commanded - returned / rate).roundToInt().coerceAtLeast(0)
            }
            progress(GuideCalibrationProgress(backPhase, i, returned))
            // Crossing the origin ends reverse motion. Further reverse pulses increase
            // the error; the endpoint and rate checks below still reject a bad return.
            if (returned >= travel || abs(travel - returned) <= max(.5f, rate * 60)) { reached = true; break }
        }
        check(reached) { "Calibration return failed" }
        check(hypot(x + returnX, y + returnY) <= max(1.5f, travel * .1f)) { "Calibration return drifted across the axis" }
        check(returnRates.size >= 3 && returnRates.sorted()[returnRates.size / 2] / rate in .8f..1.25f) {
            "Forward and reverse calibration rates disagree"
        }
        return Axis(vx, vy, samples.size - 1, travel, backlash, residual)
    }

    /** Establish motion with short probes; take-up samples never enter the calibration fit. */
    private suspend fun clearBacklash(direction: MountDirection, phase: GuideCalibrationPhase): Float {
        var pulseMs = min(config.initialPulseMs, 100)
        var commandedMs = 0
        var totalX = 0f; var totalY = 0f
        var blockX = 0f; var blockY = 0f; var blockMs = 0
        var previous: GuideVector? = null
        val rates = mutableListOf<Float>()
        val travelLimit = max(3f, config.distancePx / 2)
        repeat(80) { index ->
            check(commandedMs < MAX_CLEARING_MS) { "No consistent motion while clearing backlash" }
            val duration = min(pulseMs, MAX_CLEARING_MS - commandedMs)
            val shift = move(direction, duration)
            commandedMs += duration
            totalX += shift.x; totalY += shift.y
            val travel = hypot(totalX, totalY)
            progress(GuideCalibrationProgress(phase, index + 1, travel))
            check(travel <= travelLimit) { "Backlash clearing moved too far: $travel px (limit $travelLimit px)" }

            blockX += shift.x; blockY += shift.y; blockMs += duration
            val block = GuideVector(blockX, blockY)
            if (block.length >= .5f) {
                // Slow motion may need several short probes to rise above centroid noise.
                // Direction reversals reset the three-measurement confirmation.
                val aligned = previous?.let { block.x * it.x + block.y * it.y >= .8f * block.length * it.length } ?: true
                if (!aligned) rates.clear()
                val rate = block.length / blockMs
                rates += rate
                if (rates.size >= 3) return rates.sorted()[rates.size / 2]
                // A partly consumed backlash pulse underestimates speed. Prefer the most
                // recent response when choosing the next probe; keep its duration bounded.
                val probeRate = max(rate, shift.length / duration)
                pulseMs = ceil(1f / probeRate).toInt().coerceIn(50, 500)
                previous = block
                blockX = 0f; blockY = 0f; blockMs = 0
            } else {
                pulseMs = (pulseMs * 2).coerceAtMost(500)
            }
        }
        error("No consistent motion while clearing backlash")
    }

    private companion object {
        const val MAX_CLEARING_MS = 20_000
    }
}
