package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.mount.MountDirection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

data class GuideCalibrationConfig(val steps: Int = 7, val distancePx: Float = 15f, val initialPulseMs: Int = 1200,
    val arcsecPerPixel: Float? = null, val guideRateSidereal: Float = .5f, val declinationDeg: Float = 0f) {
    init {
        require(steps in 5..20 && distancePx in 10f..50f && initialPulseMs in 50..5000)
        require(guideRateSidereal.isFinite() && guideRateSidereal in .1f..1f)
        require(declinationDeg.isFinite() && declinationDeg in -90f..90f)
        require(arcsecPerPixel == null || arcsecPerPixel.isFinite() && arcsecPerPixel > 0f)
    }

    /** The RA component is projected onto the sky at the calibration declination. */
    fun pulseMs(ra: Boolean): Int {
        val scale = arcsecPerPixel ?: return initialPulseMs
        val projection = if (ra) cos(Math.toRadians(declinationDeg.toDouble())).toFloat().coerceAtLeast(.1f) else 1f
        return (1000f * distancePx / steps * scale / (15.041067f * guideRateSidereal * projection))
            .roundToInt().coerceIn(50, 5000)
    }
}
enum class GuideCalibrationPhase { CLEAR_EAST, EAST, WEST, CLEAR_NORTH, NORTH, SOUTH }
data class GuideCalibrationProgress(val phase: GuideCalibrationPhase, val step: Int, val distancePx: Float,
    val xPx: Float = 0f, val yPx: Float = 0f, val pulseMs: Int = 0)
data class GuideCalibrationResult(val calibration: GuideCalibration, val stars: List<GuideStar>)

/** Fits outward samples; return moves are bounded by the measured rate and tracking search radius. */
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
        val east = axis(MountDirection.EAST, MountDirection.WEST, null, GuideCalibrationPhase.EAST, GuideCalibrationPhase.WEST)
        val north = axis(MountDirection.NORTH, MountDirection.SOUTH, GuideCalibrationPhase.CLEAR_NORTH, GuideCalibrationPhase.NORTH, GuideCalibrationPhase.SOUTH)
        val calibration = GuideCalibration(east.x, east.y, north.x, north.y, config.pulseMs(true),
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

    private suspend fun axis(forward: MountDirection, reverse: MountDirection, clearPhase: GuideCalibrationPhase?,
                             outPhase: GuideCalibrationPhase, backPhase: GuideCalibrationPhase): Axis {
        val ra = forward == MountDirection.EAST
        val initialMs = config.pulseMs(ra)
        var positionX = 0f; var positionY = 0f
        if (clearPhase != null) {
            var stable = 0
            var last: GuideVector? = null
            for (i in 1..20) {
                val shift = move(forward, initialMs)
                positionX += shift.x; positionY += shift.y
                val consistent = last?.let { it.x * shift.x + it.y * shift.y > 0f } ?: true
                stable = if (shift.length >= .5f && consistent) stable + 1 else 0
                if (shift.length >= .5f) last = shift
                progress(GuideCalibrationProgress(clearPhase, i, hypot(positionX, positionY), positionX, positionY, initialMs))
                if (stable >= 3) break
            }
            check(stable >= 3) { "No consistent DEC motion while clearing backlash" }
        }
        val samples = mutableListOf(Sample(0, 0f, 0f))
        var x = 0f; var y = 0f; var totalMs = 0; var stepMs = initialMs
        for (i in 1..60) {
            val shift = move(forward, stepMs)
            x += shift.x; y += shift.y; totalMs += stepMs
            positionX += shift.x; positionY += shift.y
            samples += Sample(totalMs, x, y)
            val distance = hypot(x, y)
            progress(GuideCalibrationProgress(outPhase, i, hypot(positionX, positionY), positionX, positionY, stepMs))
            if (i >= config.steps && distance >= config.distancePx) break
            // Use measured response to approach the requested travel in the requested step count.
            if (distance > .5f) stepMs = ceil(config.distancePx / config.steps * totalMs / distance).toInt().coerceIn(50, 5000)
        }
        val travel = hypot(x, y)
        check(samples.size > config.steps && travel >= config.distancePx) { "Insufficient calibration travel" }
        // The first response can include startup latency; the remaining measured samples fit an intercept.
        val fitSamples = samples.drop(1)
        val meanT = fitSamples.map { it.ms.toDouble() }.average()
        val meanX = fitSamples.map { it.x.toDouble() }.average(); val meanY = fitSamples.map { it.y.toDouble() }.average()
        val variance = fitSamples.sumOf { (it.ms - meanT).pow(2) }
        val vx = (fitSamples.sumOf { (it.ms - meanT) * (it.x - meanX) } / variance).toFloat()
        val vy = (fitSamples.sumOf { (it.ms - meanT) * (it.y - meanY) } / variance).toFloat()
        val residual = sqrt(fitSamples.sumOf {
            (it.x - meanX - vx * (it.ms - meanT)).pow(2) + (it.y - meanY - vy * (it.ms - meanT)).pow(2)
        } / fitSamples.size).toFloat()
        check(residual <= max(.75f, travel * .1f)) { "Calibration fit is inconsistent" }
        val rate = hypot(vx, vy); check(rate > 0f && rate.isFinite()) { "Invalid calibration rate" }
        val ux = vx / rate; val uy = vy / rate
        val returnTravel = positionX * ux + positionY * uy
        check(returnTravel > 0f) { "Calibration direction is inconsistent" }
        var returned = 0f; var commanded = 0; var backlash = 0
        val returnRates = mutableListOf<Float>()
        var moving = 0; var reached = false; var wrongDirection = 0
        val tolerance = .75f
        for (i in 1..40) {
            val remaining = positionX * ux + positionY * uy
            // Retain two velocity observations before combining the remaining return into one move.
            val probeDistance = max(returnTravel / 3f, config.distancePx / config.steps * 2f)
            val distance = min(remaining, if (returnRates.size < 2) probeDistance else 20f).coerceAtMost(20f)
            val ms = ceil(distance / rate).toInt().coerceIn(20, 5000)
            val shift = move(reverse, ms)
            positionX += shift.x; positionY += shift.y
            commanded += ms
            val projected = -(shift.x * ux + shift.y * uy)
            returned += projected
            wrongDirection = if (projected < -.5f) wrongDirection + 1 else 0
            check(wrongDirection < 3) { "Return pulse moves away from the calibration origin" }
            if (projected >= rate * ms * .65f) {
                moving++
                returnRates += projected / ms
                if (moving <= 3) backlash = (commanded - returned / rate).roundToInt().coerceAtLeast(0)
            }
            progress(GuideCalibrationProgress(backPhase, i, hypot(positionX, positionY), positionX, positionY, ms))
            if (hypot(positionX, positionY) <= tolerance) { reached = true; break }
            check(returned <= returnTravel + 2f) { "Calibration return overshoot" }
            check(abs(positionX * uy - positionY * ux) <= max(1.5f, returnTravel * .1f)) {
                "Calibration return drifted across the axis"
            }
        }
        check(reached) { "Calibration return failed" }
        check(returnRates.size >= 2 && returnRates.sorted()[returnRates.size / 2] / rate in .8f..1.25f) {
            "Forward and reverse calibration rates disagree"
        }
        return Axis(vx, vy, samples.size - 1, travel, if (ra) 0 else backlash, residual)
    }
}
