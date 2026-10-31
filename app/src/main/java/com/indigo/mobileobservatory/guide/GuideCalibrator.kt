package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.mount.MountDirection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

data class GuideCalibrationConfig(val steps: Int = 12, val distancePx: Float = 25f, val initialPulseMs: Int = 1200,
    val arcsecPerPixel: Float? = null, val guideRateSidereal: Float = .5f, val declinationDeg: Float = 0f) {
    init {
        require(steps in 5..20 && distancePx in 10f..50f && initialPulseMs in 50..5000)
        require(guideRateSidereal.isFinite() && guideRateSidereal in .1f..1f)
        require(declinationDeg.isFinite() && declinationDeg in -90f..90f)
        require(arcsecPerPixel == null || arcsecPerPixel.isFinite() && arcsecPerPixel > 0f)
    }

    /** PHD2 uses one fixed pulse on both axes, rounded up to 50 ms, with at least four DEC steps. */
    fun pulseMs(@Suppress("UNUSED_PARAMETER") ra: Boolean): Int {
        val scale = arcsecPerPixel ?: return initialPulseMs
        val totalMs = 1000.0 * distancePx * scale / (15.0 * guideRateSidereal)
        val projection = cos(Math.toRadians(declinationDeg.toDouble().coerceIn(-60.0, 60.0)))
        return (ceil(min(totalMs / steps / projection, totalMs / 4.0) / 50.0) * 50.0).toInt().coerceIn(50, 5000)
    }
}
enum class GuideCalibrationPhase { WEST, EAST, CLEAR_NORTH, NORTH, SOUTH, NUDGE_SOUTH }
enum class GuideCalibrationAdvisory {
    TOO_FEW_STEPS, FIT_RESIDUAL, RA_RETURN_OFFSET, DEC_RETURN_OFFSET, REVERSE_RESPONSE, AXIS_ANGLE, BACKLASH_UNCERTAIN
}
data class GuideCalibrationProgress(val phase: GuideCalibrationPhase, val step: Int, val distancePx: Float,
    val xPx: Float = 0f, val yPx: Float = 0f, val pulseMs: Int = 0)
data class GuideCalibrationResult(val calibration: GuideCalibration, val stars: List<GuideStar>)

/** PHD2 scope.cpp cadence with bounded short-pulse DEC take-up: west/east, clear north,
 * north/south, bounded south nudges.
 * Return uses the outward command time. Residual sky drift is diagnostic, never an endless recenter target.
 * A regression residual and optional DEC backlash estimate are additional diagnostics. */
class GuideCalibrator(
    private val config: GuideCalibrationConfig,
    private val pulse: suspend (MountDirection, Int) -> Unit,
    private val measure: suspend () -> List<GuideStar>,
    private val progress: (GuideCalibrationProgress) -> Unit = {}
) {
    private val module = GuideModule()
    private var stars = emptyList<GuideStar>()
    private val advisories = linkedSetOf<GuideCalibrationAdvisory>()
    private data class Sample(val ms: Int, val x: Float, val y: Float)
    private data class Axis(val x: Float, val y: Float, val steps: Int, val travel: Float,
        val backlash: Int, val residual: Float, val returnResidual: Float)

    suspend fun run(): GuideCalibrationResult {
        stars = measure(); check(stars.isNotEmpty()) { "No usable calibration stars" }
        val west = axis(MountDirection.WEST, MountDirection.EAST, null, GuideCalibrationPhase.WEST, GuideCalibrationPhase.EAST)
        val north = axis(MountDirection.NORTH, MountDirection.SOUTH, west, GuideCalibrationPhase.NORTH, GuideCalibrationPhase.SOUTH)
        val calibration = GuideCalibration(-west.x, -west.y, north.x, north.y, config.pulseMs(true),
            west.steps, north.steps, west.travel, north.travel, 0, north.backlash, max(west.residual, north.residual),
            west.returnResidual, north.returnResidual)
        check(module.projectError(calibration, 0f, 0f) != null) { "Calibration axes are degenerate" }
        val cosine = abs(west.x * north.x + west.y * north.y) / (hypot(west.x, west.y) * hypot(north.x, north.y))
        if (cosine > sin(Math.toRadians(12.5)).toFloat()) advisories += GuideCalibrationAdvisory.AXIS_ANGLE
        return GuideCalibrationResult(calibration.copy(advisories = advisories.toList()), stars)
    }

    private suspend fun move(direction: MountDirection, ms: Int): GuideVector {
        currentCoroutineContext().ensureActive()
        pulse(direction, ms)
        val next = measure()
        val shift = module.matchShift(stars, next) ?: error("Calibration star lost")
        stars = next
        return shift
    }

    private suspend fun axis(forward: MountDirection, reverse: MountDirection, west: Axis?,
        outPhase: GuideCalibrationPhase, backPhase: GuideCalibrationPhase): Axis {
        val ra = west == null
        val stepMs = config.pulseMs(ra)
        var positionX = 0f; var positionY = 0f
        fun report(phase: GuideCalibrationPhase, step: Int, ms: Int) =
            progress(GuideCalibrationProgress(phase, step, hypot(positionX, positionY), positionX, positionY, ms))
        val samples = mutableListOf(Sample(0, 0f, 0f))
        var x = 0f; var y = 0f; var totalMs = 0; var count = 0
        if (!ra) {
            val clearing = clearDecBacklash()
            positionX = clearing.x
            positionY = clearing.y
        }
        val clearingX = positionX - x; val clearingY = positionY - y
        while (hypot(x, y) < config.distancePx && count < 60) {
            val shift = move(forward, stepMs)
            x += shift.x; y += shift.y; totalMs += stepMs; count++
            positionX += shift.x; positionY += shift.y
            samples += Sample(totalMs, x, y)
            report(outPhase, count, stepMs)
        }
        val travel = hypot(x, y)
        check(travel >= config.distancePx) { "Insufficient calibration travel" }
        if (count < 4) advisories += GuideCalibrationAdvisory.TOO_FEW_STEPS
        val fit = samples.drop(1)
        val meanT = fit.map { it.ms.toDouble() }.average()
        val meanX = fit.map { it.x.toDouble() }.average(); val meanY = fit.map { it.y.toDouble() }.average()
        val variance = fit.sumOf { (it.ms - meanT).pow(2) }
        // PHD2 derives the response from outward travel / total outward command time.
        val vx = x / totalMs; val vy = y / totalMs
        val fitVx = if (variance > 0.0) (fit.sumOf { (it.ms - meanT) * (it.x - meanX) } / variance).toFloat() else vx
        val fitVy = if (variance > 0.0) (fit.sumOf { (it.ms - meanT) * (it.y - meanY) } / variance).toFloat() else vy
        val residual = sqrt(fit.sumOf {
            (it.x - meanX - fitVx * (it.ms - meanT)).pow(2) + (it.y - meanY - fitVy * (it.ms - meanT)).pow(2)
        } / fit.size).toFloat()
        if (residual > max(.75f, travel * .1f)) advisories += GuideCalibrationAdvisory.FIT_RESIDUAL
        val rate = hypot(vx, vy); check(rate > 0f && rate.isFinite()) { "Invalid calibration rate" }
        val ux = vx / rate; val uy = vy / rate
        val northExtent = hypot(positionX, positionY)
        val northUx = if (northExtent > 0) positionX / northExtent else ux
        val northUy = if (northExtent > 0) positionY / northExtent else uy
        var returned = 0f; var commanded = 0; var backlash = 0
        var returnX = 0f; var returnY = 0f
        val returnRates = mutableListOf<Float>()
        var remainingMs = totalMs; var returnStep = 0
        val maxReturnMs = floor((if (ra) 20f else 16f) / rate).toInt().coerceIn(1, 5000)
        suspend fun reverseMove(ms: Int, phase: GuideCalibrationPhase, step: Int) {
            val shift = move(reverse, ms)
            positionX += shift.x; positionY += shift.y
            returnX += shift.x; returnY += shift.y; commanded += ms
            val projected = -(shift.x * ux + shift.y * uy)
            returned += projected
            if (projected >= rate * ms * .65f) {
                returnRates += projected / ms
                if (returnRates.size <= 3) backlash = (commanded - returned / rate).roundToInt().coerceAtLeast(0)
            }
            report(phase, step, ms)
        }
        while (remainingMs > 0) {
            val ms = min(remainingMs, maxReturnMs)
            reverseMove(ms, backPhase, ++returnStep)
            remainingMs -= ms
            // A faster reverse response must not drive farther after crossing the origin.
            if (returned >= travel + hypot(clearingX, clearingY)) break
        }
        if (!ra) {
            // PHD2's bounded south nudging: 2 px, approximately the south direction, no direction reversal.
            // Its <= MAX_NUDGES test permits four attempts (counter starts at zero).
            for (i in 1..4) {
                val distance = hypot(positionX, positionY)
                val cosNorth = if (distance > 0) (positionX * northUx + positionY * northUy) / distance else 0f
                val cross = west!!.x * uy - west.y * ux
                val decDistance = if (abs(cross) > 1e-9f) (west.x * positionY - west.y * positionX) / cross else 0f
                if (distance <= 2f || distance >= travel + hypot(clearingX, clearingY) ||
                    cosNorth <= cos(Math.toRadians(40.0)).toFloat() || decDistance <= 0f || returned <= 0f) break
                val ms = floor(min(decDistance, 20f) / rate).toInt().coerceAtMost(stepMs)
                if (ms <= 0) break
                reverseMove(ms, GuideCalibrationPhase.NUDGE_SOUTH, i)
            }
        }
        val reverseDistance = hypot(returnX, returnY)
        val reverseCosine = if (reverseDistance > 0) -(returnX * ux + returnY * uy) / reverseDistance else 0f
        if (returned < travel * .25f || reverseCosine < cos(Math.toRadians(30.0)).toFloat())
            advisories += GuideCalibrationAdvisory.REVERSE_RESPONSE
        val returnResidual = hypot(positionX, positionY)
        if (returnResidual > 2f) advisories += if (ra) GuideCalibrationAdvisory.RA_RETURN_OFFSET else GuideCalibrationAdvisory.DEC_RETURN_OFFSET
        val reverseRateRatio = returnRates.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] / rate }
        if (!ra && (returnRates.isEmpty() || reverseRateRatio !in .8f..1.25f || reverseCosine < .95f)) {
            backlash = 0
            advisories += GuideCalibrationAdvisory.BACKLASH_UNCERTAIN
        }
        if (!ra && GuideCalibrationAdvisory.BACKLASH_UNCERTAIN in advisories) backlash = 0
        return Axis(vx, vy, count, travel, if (ra) 0 else backlash, residual, returnResidual)
    }

    /** Confirm DEC motion using short probes, excluded from the fixed-pulse calibration fit. */
    private suspend fun clearDecBacklash(): GuideVector {
        var pulseMs = min(config.pulseMs(false), 100)
        var commandedMs = 0
        var totalX = 0f; var totalY = 0f
        var blockX = 0f; var blockY = 0f; var blockMs = 0
        var previous: GuideVector? = null
        var accepted = 0
        val travelLimit = max(3f, config.distancePx / 2)
        repeat(80) { index ->
            check(commandedMs < MAX_CLEARING_MS) { "No consistent motion while clearing backlash" }
            val duration = min(pulseMs, MAX_CLEARING_MS - commandedMs)
            val shift = move(MountDirection.NORTH, duration)
            commandedMs += duration
            totalX += shift.x; totalY += shift.y
            val travel = hypot(totalX, totalY)
            progress(GuideCalibrationProgress(GuideCalibrationPhase.CLEAR_NORTH, index + 1,
                travel, totalX, totalY, duration))
            check(travel <= travelLimit) { "Backlash clearing moved too far: $travel px (limit $travelLimit px)" }
            blockX += shift.x; blockY += shift.y; blockMs += duration
            val block = GuideVector(blockX, blockY)
            if (block.length >= .5f) {
                val aligned = previous?.let {
                    block.x * it.x + block.y * it.y >= .8f * block.length * it.length
                } ?: true
                accepted = if (aligned) accepted + 1 else 1
                if (accepted >= 3) return GuideVector(totalX, totalY)
                val probeRate = max(block.length / blockMs, shift.length / duration)
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
