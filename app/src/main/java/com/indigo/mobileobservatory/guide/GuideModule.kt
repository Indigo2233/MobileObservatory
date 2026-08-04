package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.mount.MountDirection
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sqrt

data class GuideStar(
    val x: Float,
    val y: Float,
    val snr: Float,
    val flux: Float = snr
)

data class GuideCorrection(
    val dxPx: Float,
    val dyPx: Float,
    val raPulseMs: Int,
    val decPulseMs: Int,
    val raDirection: MountDirection?,
    val decDirection: MountDirection?
)

data class GuideHistoryPoint(
    val timestampMs: Long,
    val raErrorPx: Float,
    val decErrorPx: Float
)

enum class GuideAlgorithm {
    HYSTERESIS,
    LOW_PASS,
    RESIST_SWITCH,
    PREDICTIVE_RA
}

enum class GuideCalibrationState {
    IDLE,
    RUNNING,
    COMPLETE,
    FAILED
}

data class GuideCalibration(
    val eastXPerMs: Float,
    val eastYPerMs: Float,
    val northXPerMs: Float,
    val northYPerMs: Float,
    val pulseMs: Int
) {
    val eastRatePxPerSec: Float get() = hypot(eastXPerMs, eastYPerMs) * 1000f
    val northRatePxPerSec: Float get() = hypot(northXPerMs, northYPerMs) * 1000f
}

data class GuideVector(val x: Float, val y: Float) {
    val length: Float get() = hypot(x, y)
}

data class GuideRms(val raPx: Float, val decPx: Float, val totalPx: Float)

/** Pure guide-domain implementation: star detection, matching, calibration projection and filtering. */
class GuideModule {
    private var lastRaOutput = 0f
    private var lastDecOutput = 0f
    private var lastRaError = 0f
    private var lastDecError = 0f

    fun detectStars(frame: FrameData, maxStars: Int = 16): List<GuideStar> {
        val width = frame.width
        val height = frame.height
        if (width < 16 || height < 16) return emptyList()
        if (frame.data.size < width * height * frame.pixelFormat.bytesPerPixel) return emptyList()

        var sum = 0.0
        var sumSquared = 0.0
        var samples = 0
        for (y in 0 until height step 8) {
            for (x in 0 until width step 8) {
                val value = rawValue(frame, x, y).toDouble()
                sum += value
                sumSquared += value * value
                samples++
            }
        }
        if (samples == 0) return emptyList()

        val mean = sum / samples
        val sigma = sqrt((sumSquared / samples - mean * mean).coerceAtLeast(0.0))
        val threshold = mean + sigma * 4.0
        val candidates = ArrayList<Candidate>()
        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                val value = rawValue(frame, x, y).toDouble()
                if (value <= threshold) continue
                if (value >= rawValue(frame, x - 1, y) &&
                    value >= rawValue(frame, x + 1, y) &&
                    value >= rawValue(frame, x, y - 1) &&
                    value >= rawValue(frame, x, y + 1)) {
                    candidates += Candidate(x, y, value)
                }
            }
        }

        val stars = ArrayList<GuideStar>()
        for (candidate in candidates.sortedByDescending { it.value }) {
            if (stars.size >= maxStars) break
            if (stars.any { hypot(it.x - candidate.x, it.y - candidate.y) < 24f }) continue
            var weightSum = 0.0
            var xSum = 0.0
            var ySum = 0.0
            for (y in (candidate.y - 10).coerceAtLeast(0)..(candidate.y + 10).coerceAtMost(height - 1)) {
                for (x in (candidate.x - 10).coerceAtLeast(0)..(candidate.x + 10).coerceAtMost(width - 1)) {
                    val weight = (rawValue(frame, x, y) - threshold).coerceAtLeast(0.0)
                    weightSum += weight
                    xSum += x * weight
                    ySum += y * weight
                }
            }
            if (weightSum > 0.0) {
                stars += GuideStar(
                    x = (xSum / weightSum).toFloat(),
                    y = (ySum / weightSum).toFloat(),
                    snr = ((candidate.value - mean) / sigma.coerceAtLeast(1.0)).toFloat(),
                    flux = weightSum.toFloat()
                )
            }
        }
        return stars.sortedByDescending { it.flux }
    }

    fun matchShift(reference: List<GuideStar>, current: List<GuideStar>): GuideVector? {
        if (reference.isEmpty() || current.isEmpty()) return null
        var xSum = 0.0
        var ySum = 0.0
        var weightSum = 0.0
        val used = HashSet<Int>()
        reference.forEach { ref ->
            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE
            current.forEachIndexed { index, star ->
                if (index !in used) {
                    val distance = hypot(star.x - ref.x, star.y - ref.y)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestIndex = index
                    }
                }
            }
            if (bestIndex >= 0 && bestDistance <= 60f) {
                used += bestIndex
                val star = current[bestIndex]
                val weight = ref.flux.coerceAtLeast(1f).toDouble()
                xSum += (star.x - ref.x) * weight
                ySum += (star.y - ref.y) * weight
                weightSum += weight
            }
        }
        if (weightSum <= 0.0) return null
        return GuideVector((xSum / weightSum).toFloat(), (ySum / weightSum).toFloat())
    }

    fun projectError(calibration: GuideCalibration, dx: Float, dy: Float): Pair<Float, Float>? {
        val determinant = calibration.eastXPerMs * calibration.northYPerMs -
            calibration.northXPerMs * calibration.eastYPerMs
        if (abs(determinant) < 0.000001f) return null
        val targetX = -dx
        val targetY = -dy
        return (targetX * calibration.northYPerMs - calibration.northXPerMs * targetY) / determinant to
            (calibration.eastXPerMs * targetY - targetX * calibration.eastYPerMs) / determinant
    }

    fun filter(
        errorMs: Float,
        raAxis: Boolean,
        algorithm: GuideAlgorithm,
        aggressiveness: Float
    ): Float {
        val previous = if (raAxis) lastRaOutput else lastDecOutput
        val previousError = if (raAxis) lastRaError else lastDecError
        val result = when (algorithm) {
            GuideAlgorithm.HYSTERESIS -> errorMs * aggressiveness + previous * 0.10f
            GuideAlgorithm.LOW_PASS -> (previous * 0.65f + errorMs * 0.35f) * aggressiveness
            GuideAlgorithm.RESIST_SWITCH -> {
                val base = errorMs * aggressiveness
                if (previous != 0f && sign(base) != sign(previous) && abs(base) < abs(previous) * 1.5f) {
                    base * 0.35f
                } else base
            }
            GuideAlgorithm.PREDICTIVE_RA -> {
                // Only RA gets a light first-difference prediction; DEC stays proportional.
                val predicted = if (raAxis) errorMs + (errorMs - previousError) * 0.35f else errorMs
                predicted * aggressiveness
            }
        }
        if (raAxis) {
            lastRaOutput = result
            lastRaError = errorMs
        } else {
            lastDecOutput = result
            lastDecError = errorMs
        }
        return result
    }

    fun rms(points: List<GuideHistoryPoint>): GuideRms {
        if (points.isEmpty()) return GuideRms(0f, 0f, 0f)
        val ra = sqrt(points.sumOf { (it.raErrorPx * it.raErrorPx).toDouble() } / points.size).toFloat()
        val dec = sqrt(points.sumOf { (it.decErrorPx * it.decErrorPx).toDouble() } / points.size).toFloat()
        return GuideRms(ra, dec, hypot(ra, dec))
    }

    fun resetFilter() {
        lastRaOutput = 0f
        lastDecOutput = 0f
        lastRaError = 0f
        lastDecError = 0f
    }

    private fun rawValue(frame: FrameData, x: Int, y: Int): Int {
        val index = y * frame.width + x
        return when {
            frame.pixelFormat == PixelFormat.RGB48 -> {
                val base = index * 6
                if (base + 5 >= frame.data.size) return 0
                val red = u16(frame.data, base)
                val green = u16(frame.data, base + 2)
                val blue = u16(frame.data, base + 4)
                (red * 299 + green * 587 + blue * 114) / 1000
            }
            frame.pixelFormat.bytesPerPixel == 1 ->
                if (index >= frame.data.size) 0 else frame.data[index].toInt() and 0xFF
            else -> {
                val base = index * 2
                if (base + 1 >= frame.data.size) 0 else u16(frame.data, base)
            }
        }
    }

    private fun u16(data: ByteArray, index: Int): Int =
        (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)

    private data class Candidate(val x: Int, val y: Int, val value: Double)
}
