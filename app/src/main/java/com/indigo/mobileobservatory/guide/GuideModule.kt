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
    val flux: Float = snr,
    val radiusPx: Float = Float.NaN,
    val saturated: Boolean = false
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

    fun detectStars(frame: FrameData, maxStars: Int = 16, saturationLevel: Int? = null): List<GuideStar> {
        val width = frame.width
        val height = frame.height
        if (width < 16 || height < 16) return emptyList()
        if (frame.data.size.toLong() < width.toLong() * height * frame.pixelFormat.bytesPerPixel) return emptyList()

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
            var squaredRadiusSum = 0.0
            for (y in (candidate.y - 10).coerceAtLeast(0)..(candidate.y + 10).coerceAtMost(height - 1)) {
                for (x in (candidate.x - 10).coerceAtLeast(0)..(candidate.x + 10).coerceAtMost(width - 1)) {
                    val weight = (rawValue(frame, x, y) - threshold).coerceAtLeast(0.0)
                    weightSum += weight
                    xSum += x * weight
                    ySum += y * weight
                    squaredRadiusSum += (x.toDouble() * x + y.toDouble() * y) * weight
                }
            }
            if (weightSum > 0.0) {
                stars += GuideStar(
                    x = (xSum / weightSum).toFloat(),
                    y = (ySum / weightSum).toFloat(),
                    snr = ((candidate.value - mean) / sigma.coerceAtLeast(1.0)).toFloat(),
                    flux = weightSum.toFloat(),
                    radiusPx = sqrt((squaredRadiusSum / weightSum - (xSum / weightSum).let { it * it } -
                        (ySum / weightSum).let { it * it }).coerceAtLeast(0.0)).toFloat(),
                    saturated = run {
                        val peak = peakValue(frame, candidate.x, candidate.y)
                        val nominal = (1 shl frame.pixelFormat.nativeBits) - 1
                        val maximum = saturationLevel ?: if (peak > nominal) 65535 else nominal
                        peak >= maximum - maxOf(1, maximum / 1000)
                    }
                )
            }
        }
        return stars.sortedByDescending { it.flux }
    }

    fun matchShift(reference: List<GuideStar>, current: List<GuideStar>): GuideVector? {
        if (reference.isEmpty() || current.isEmpty()) return null
        data class Shift(val dx: Float, val dy: Float, val weight: Double)
        val shifts = ArrayList<Shift>()
        val used = HashSet<Int>()
        reference.forEach { ref ->
            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE
            current.forEachIndexed { index, star ->
                if (index !in used && star.flux.isFinite() && ref.flux > 0 && star.flux / ref.flux in .2f..5f) {
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
                shifts += Shift(star.x - ref.x, star.y - ref.y, weight)
            }
        }
        if (shifts.isEmpty()) return null
        fun median(values: List<Float>): Float = values.sorted().let { sorted ->
            if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * .5f else sorted[sorted.size / 2]
        }
        val x = median(shifts.map { it.dx }); val y = median(shifts.map { it.dy })
        val inliers = shifts.filter { hypot(it.dx - x, it.dy - y) <= 2.5f }
        if (inliers.isEmpty() || inliers.size * 2 < shifts.size) return null
        val weight = inliers.sumOf { it.weight }
        return GuideVector((inliers.sumOf { it.dx * it.weight } / weight).toFloat(),
            (inliers.sumOf { it.dy * it.weight } / weight).toFloat())
    }

    fun projectError(calibration: GuideCalibration, dx: Float, dy: Float): Pair<Float, Float>? {
        val determinant = calibration.eastXPerMs * calibration.northYPerMs -
            calibration.northXPerMs * calibration.eastYPerMs
        val east = hypot(calibration.eastXPerMs, calibration.eastYPerMs)
        val north = hypot(calibration.northXPerMs, calibration.northYPerMs)
        if (!determinant.isFinite() || !east.isFinite() || !north.isFinite() || east <= 0f || north <= 0f ||
            abs(determinant.toDouble()) / (east.toDouble() * north) < .2 || !dx.isFinite() || !dy.isFinite()) return null
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

    fun usableStars(stars: List<GuideStar>): List<GuideStar> = stars.filter {
        it.x.isFinite() && it.y.isFinite() && it.snr.isFinite() && it.snr >= 5f &&
            it.flux.isFinite() && it.flux > 0 && !it.saturated && (it.radiusPx.isNaN() || it.radiusPx in .6f..8f)
    }

    private fun rawValue(frame: FrameData, x: Int, y: Int): Int {
        val index = y * frame.width + x
        return when {
            frame.pixelFormat == PixelFormat.RGB24 -> {
                val base = index * 3
                ((frame.data[base].toInt() and 255) * 299 + (frame.data[base + 1].toInt() and 255) * 587 +
                    (frame.data[base + 2].toInt() and 255) * 114) / 1000
            }
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

    private fun peakValue(frame: FrameData, x: Int, y: Int): Int {
        val index = (y * frame.width + x) * frame.pixelFormat.bytesPerPixel
        return when (frame.pixelFormat) {
            PixelFormat.RGB24 -> maxOf(frame.data[index].toInt() and 255, frame.data[index + 1].toInt() and 255, frame.data[index + 2].toInt() and 255)
            PixelFormat.RGB48 -> maxOf(u16(frame.data, index), u16(frame.data, index + 2), u16(frame.data, index + 4))
            else -> rawValue(frame, x, y)
        }
    }

    private fun u16(data: ByteArray, index: Int): Int =
        (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)

    private data class Candidate(val x: Int, val y: Int, val value: Double)
}
