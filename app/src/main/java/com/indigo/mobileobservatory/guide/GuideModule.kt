package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.mount.MountDirection
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.roundToInt

data class GuideStar(
    val x: Float,
    val y: Float,
    val snr: Float,
    val flux: Float = snr,
    val radiusPx: Float = Float.NaN,
    val saturated: Boolean = false,
    val hfdPx: Float = Float.NaN,
    val peakFraction: Float = Float.NaN
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
    val decErrorPx: Float,
    val raPulseMs: Int = 0,
    val decPulseMs: Int = 0,
    val raCorrectionPx: Float = 0f,
    val decCorrectionPx: Float = 0f
)

enum class GuideAlgorithm {
    IDENTITY,
    HYSTERESIS,
    LOW_PASS,
    LOW_PASS_2,
    Z_FILTER,
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
    val pulseMs: Int,
    val eastSteps: Int = 0,
    val northSteps: Int = 0,
    val eastTravelPx: Float = 0f,
    val northTravelPx: Float = 0f,
    val raBacklashMs: Int = 0,
    val decBacklashMs: Int = 0,
    val fitResidualPx: Float = 0f,
    val raReturnResidualPx: Float = 0f,
    val decReturnResidualPx: Float = 0f,
    val advisories: List<GuideCalibrationAdvisory> = emptyList()
) {
    val eastRatePxPerSec: Float get() = hypot(eastXPerMs, eastYPerMs) * 1000f
    val northRatePxPerSec: Float get() = hypot(northXPerMs, northYPerMs) * 1000f
}

data class GuideVector(val x: Float, val y: Float) {
    val length: Float get() = hypot(x, y)
}

data class GuideRms(val raPx: Float, val decPx: Float, val totalPx: Float)
data class GuideSolution(val raErrorPx: Float, val decErrorPx: Float, val correction: GuideCorrection)

/** Pure guide-domain implementation: star detection, matching, calibration projection and filtering. */
class GuideModule {
    private val raFilter = Phd2GuideFilter(true)
    private val decFilter = Phd2GuideFilter(false)

    fun detectStars(frame: FrameData, maxStars: Int = 16, saturationLevel: Int? = null,
        preferred: GuideStar? = null, includeRejected: Boolean = false): List<GuideStar> {
        val width = frame.width
        val height = frame.height
        if (width < 16 || height < 16) return emptyList()
        if (frame.data.size.toLong() < width.toLong() * height * frame.pixelFormat.bytesPerPixel) return emptyList()

        val image = GuideImage(frame)
        var sum = 0.0; var square = 0.0; var count = 0
        val phaseSums = DoubleArray(4)
        var phaseCount = 0
        for (y in 0 until height step 8) for (x in 0 until width step 8) {
            val value = image.value(x, y).toDouble()
            sum += value; square += value * value; count++
            if (image.bayer && x + 1 < width && y + 1 < height) {
                for (dy in 0..1) for (dx in 0..1)
                    phaseSums[dy * 2 + dx] += rawValue(frame, x + dx, y + dy).toDouble()
                phaseCount++
            }
        }
        if (count == 0) return emptyList()
        val mean = sum / count
        val threshold = mean + 4 * sqrt((square / count - mean * mean).coerceAtLeast(0.0))
        val signal = if (image.bayer) GuideCandidateMask(frame, DoubleArray(4) { phaseSums[it] / phaseCount }, threshold) else null
        val candidates = ArrayList<Candidate>()
        for (y in 2 until height - 2) {
            signal?.prepareRow(y)
            for (x in 2 until width - 2) {
                if (signal != null && !signal.mayContainSignal(x)) continue
                val value = image.value(x, y).toDouble()
                if (value <= threshold) continue
                if (value >= image.value(x - 1, y) && value >= image.value(x + 1, y) &&
                    value >= image.value(x, y - 1) && value >= image.value(x, y + 1)) {
                    candidates += Candidate(x, y, value)
                }
            }
        }
        // Tracking a selected target uses local photometry even below the global discovery threshold.
        if (preferred != null && preferred.x.isFinite() && preferred.y.isFinite()) {
            val x = (preferred.x - image.sensorOffset).roundToInt()
            val y = (preferred.y - image.sensorOffset).roundToInt()
            if (x in 12 until width - 12 && y in 12 until height - 12)
                candidates += Candidate(x, y, image.value(x, y).toDouble())
        }

        val stars = ArrayList<GuideStar>()
        val rejected = ArrayList<GuideStar>()
        // Reserve the selected target before the global brightness limit is applied.
        val ordered = if (preferred == null) candidates.sortedByDescending { it.value } else candidates.sortedWith(
            compareBy<Candidate> {
                val distance = hypot(it.x + image.sensorOffset - preferred.x, it.y + image.sensorOffset - preferred.y)
                if (distance <= 24.0) distance else Double.MAX_VALUE
            }.thenByDescending { it.value })
        for (candidate in ordered) {
            if (stars.size >= maxStars) break
            if (candidate.x < 12 || candidate.y < 12 || candidate.x >= width - 12 || candidate.y >= height - 12) continue
            if (stars.any { hypot(it.x - candidate.x, it.y - candidate.y) < 24f }) continue
            var aperturePeak = 0
            for (y in (candidate.y - 10).coerceAtLeast(0)..(candidate.y + 10).coerceAtMost(height - 1)) {
                for (x in (candidate.x - 10).coerceAtLeast(0)..(candidate.x + 10).coerceAtMost(width - 1)) {
                    aperturePeak = maxOf(aperturePeak, peakValue(frame, x, y))
                }
            }
            val nominal = (1 shl frame.pixelFormat.nativeBits) - 1
            val maximum = saturationLevel ?: if (aperturePeak > nominal) 65535 else nominal
            val measured = Phd2StarMeasurement.find(image, candidate.x, candidate.y, maxADU = maximum)
            if (measured.mass <= 0 || !measured.x.isFinite() || !measured.y.isFinite()) continue
            val centerX = (measured.x + image.sensorOffset).toFloat()
            val centerY = (measured.y + image.sensorOffset).toFloat()
            if (centerX.roundToInt() !in 12..width - 13 || centerY.roundToInt() !in 12..height - 13) continue
            if (stars.any { hypot(it.x - centerX, it.y - centerY) < 24f } ||
                rejected.any { hypot(it.x - centerX, it.y - centerY) < 1f }) continue
            val star = GuideStar(centerX, centerY, measured.snr.toFloat(), measured.mass.toFloat(), measured.radius.toFloat(),
                measured.result == Phd2StarMeasurement.Result.SATURATED || aperturePeak >= maximum - maxOf(1, maximum / 1000),
                measured.hfd.toFloat(), aperturePeak.toFloat() / maximum)
            if (measured.found && isUsableStar(star)) stars += star else if (rejected.size < maxStars) rejected += star
        }
        return (if (includeRejected) stars + rejected else stars.ifEmpty { rejected }).sortedByDescending { it.flux }
    }

    /** Track an explicitly selected star without replacing it with a brighter neighbour. */
    fun matchStar(reference: GuideStar, current: List<GuideStar>): GuideStar? {
        if (!reference.flux.isFinite() || reference.flux <= 0f) return null
        return current.filter { it.flux / reference.flux in .2f..5f &&
            hypot(it.x - reference.x, it.y - reference.y) <= 60f }
            .minByOrNull { hypot(it.x - reference.x, it.y - reference.y) }
    }

    fun referenceStars(primary: GuideStar, stars: List<GuideStar>, multiStar: Boolean): List<GuideStar> =
        if (multiStar) (listOf(primary) + stars.filter { it != primary }).take(12) else listOf(primary)

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
        errorPx: Float,
        raAxis: Boolean,
        algorithm: GuideAlgorithm,
        aggressiveness: Float,
        minMovePx: Float = 0f,
        parameters: GuideFilterParameters = GuideFilterParameters(),
        snr: Float = 20f,
        exposureSeconds: Double = 1.0,
        timestampSeconds: Double = System.nanoTime() / 1e9
    ): Float {
        return (if (raAxis) raFilter else decFilter).result(errorPx.toDouble(), algorithm, aggressiveness.toDouble(),
            minMovePx.toDouble(), parameters, snr.toDouble(), exposureSeconds, timestampSeconds).toFloat()
    }

    /** Shared by the live camera loop and recorded-image replays. Only accepted pulses update backlash state. */
    fun solveCorrection(calibration: GuideCalibration, dx: Float, dy: Float, algorithm: GuideAlgorithm,
        raAggressiveness: Float, decAggressiveness: Float, minMovePx: Float,
        backlash: GuideBacklashCompensation, backlashEnabled: Boolean = true,
        reverseRa: Boolean = false, reverseDec: Boolean = false,
        decAlgorithm: GuideAlgorithm = algorithm, decMinMovePx: Float = minMovePx,
        raParameters: GuideFilterParameters = GuideFilterParameters(), decParameters: GuideFilterParameters = GuideFilterParameters(),
        snr: Float = 20f, exposureSeconds: Double = 1.0, timestampSeconds: Double = System.nanoTime() / 1e9,
        maxRaPulseMs: Int = 2500, maxDecPulseMs: Int = 2500): GuideSolution? {
        val axes = projectError(calibration, dx, dy) ?: return null
        val raError = -axes.first * hypot(calibration.eastXPerMs, calibration.eastYPerMs)
        val decError = -axes.second * hypot(calibration.northXPerMs, calibration.northYPerMs)
        val raRate = hypot(calibration.eastXPerMs, calibration.eastYPerMs)
        val decRate = hypot(calibration.northXPerMs, calibration.northYPerMs)
        val ra = filter(-raError, true, algorithm, raAggressiveness, minMovePx, raParameters, snr, exposureSeconds, timestampSeconds) / raRate
        val dec = filter(-decError, false, decAlgorithm, decAggressiveness, decMinMovePx, decParameters, snr, exposureSeconds, timestampSeconds) / decRate
        val raMs = abs(ra).roundToInt().coerceIn(0, maxRaPulseMs.coerceIn(1, 5000))
        val decMs = abs(dec).roundToInt().coerceIn(0, maxDecPulseMs.coerceIn(1, 5000))
        val raDirection = if (raMs == 0) null else if ((ra > 0) xor reverseRa) MountDirection.EAST else MountDirection.WEST
        val decDirection = if (decMs == 0) null else if ((dec > 0) xor reverseDec) MountDirection.NORTH else MountDirection.SOUTH
        return GuideSolution(raError, decError, GuideCorrection(dx, dy, raMs,
            backlash.pulse(decMs, decDirection, calibration.decBacklashMs, backlashEnabled), raDirection, decDirection))
    }

    fun rms(points: List<GuideHistoryPoint>): GuideRms {
        if (points.isEmpty()) return GuideRms(0f, 0f, 0f)
        val ra = sqrt(points.sumOf { (it.raErrorPx * it.raErrorPx).toDouble() } / points.size).toFloat()
        val dec = sqrt(points.sumOf { (it.decErrorPx * it.decErrorPx).toDouble() } / points.size).toFloat()
        return GuideRms(ra, dec, hypot(ra, dec))
    }

    fun resetFilter(raAxis: Boolean? = null) {
        if (raAxis != false) raFilter.reset()
        if (raAxis != true) decFilter.reset()
    }

    fun usableStars(stars: List<GuideStar>): List<GuideStar> = stars.filter(::isUsableStar)

    fun autoSelectStar(stars: List<GuideStar>): GuideStar? {
        val eligible = usableStars(stars).filter { it.snr >= 6f }
        return eligible.firstOrNull { it.peakFraction.isNaN() || it.peakFraction < .9f } ?: eligible.firstOrNull()
    }

    private fun isUsableStar(star: GuideStar): Boolean =
        star.x.isFinite() && star.y.isFinite() && star.snr.isFinite() && star.snr >= 3f &&
            star.flux.isFinite() && star.flux > 0 && !star.saturated && (star.radiusPx.isNaN() || star.radiusPx in .6f..8f) &&
            (star.hfdPx.isNaN() || star.hfdPx in 1.5f..20f)

    internal fun rawValue(frame: FrameData, x: Int, y: Int): Int = GuidePixelAccess.raw(frame, x, y)

    private fun peakValue(frame: FrameData, x: Int, y: Int): Int = GuidePixelAccess.peak(frame, x, y)

    private data class Candidate(val x: Int, val y: Int, val value: Double)
}
