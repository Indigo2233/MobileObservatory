package com.indigo.mobileobservatory.solar

import java.time.Instant
import java.security.MessageDigest
import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

enum class SolarScanDirection {
    A_TO_B,
    B_TO_A,
    UNKNOWN;

    fun next(): SolarScanDirection =
        if (this == A_TO_B) B_TO_A else A_TO_B

    /** Alternating directions are normalized before they enter a result. */
    val flipVertical: Boolean get() = this == B_TO_A
}

enum class SolarSourcePolicy {
    KEEP,
    TRASH_AFTER_SEVEN_DAYS,
    DELETE_AFTER_COMMIT
}

enum class SolarOutputProduct {
    DISPLAY_PNG,
    LINEAR_FITS,
    LINEAR_PNG
}

enum class SolarScanPhase {
    IDLE,
    PREFLIGHT,
    ANALYZING,
    MOVING_TO_START,
    RECORDING,
    SETTLING,
    RECONSTRUCTING,
    COMMITTING,
    COMPLETED,
    STOPPING,
    CANCELLED,
    FAILED
}

data class SolarScanEndpoint(
    val raHours: Double,
    val decDegrees: Double
) {
    init {
        require(raHours.isFinite() && decDegrees.isFinite()) { "Endpoint coordinates must be finite." }
        require(raHours in 0.0..24.0 && decDegrees in -90.0..90.0) {
            "Endpoint coordinates are outside the equatorial range."
        }
    }
}

data class SolarRoi(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int,
    val height: Int
) {
    init {
        require(x >= 0 && y >= 0 && width > 0 && height > 0) { "Solar ROI is invalid." }
    }

    val right: Int get() = Math.addExact(x, width)
    val bottom: Int get() = Math.addExact(y, height)
    val pixels: Long get() = width.toLong() * height
}

data class SolarScanConfiguration(
    val endpointA: SolarScanEndpoint,
    val endpointB: SolarScanEndpoint,
    val scanDurationMs: Long = 6_000,
    val diskCrossingMs: Long = 4_500,
    val diskDiameterArcsec: Double = 1_920.0,
    val startBlankArcsec: Double = 60.0,
    val endBlankArcsec: Double = 60.0,
    val positionUncertaintyArcsec: Double = 30.0,
    val frameRate: Double = 200.0,
    val exposureUs: Long = 3_000,
    val roi: SolarRoi,
    val pixelBytes: Int = 2,
    val legCount: Int = 2,
    val continuous: Boolean = false,
    val sourcePolicy: SolarSourcePolicy = SolarSourcePolicy.KEEP,
    val products: Set<SolarOutputProduct> = setOf(
        SolarOutputProduct.DISPLAY_PNG,
        SolarOutputProduct.LINEAR_FITS
    )
) {
    init {
        require(scanDurationMs > 0) { "Scan duration must be positive." }
        require(diskCrossingMs > 0) { "Disk crossing duration must be positive." }
        require(diskDiameterArcsec.isFinite() && diskDiameterArcsec > 0)
        require(startBlankArcsec.isFinite() && startBlankArcsec >= 0)
        require(endBlankArcsec.isFinite() && endBlankArcsec >= 0)
        require(positionUncertaintyArcsec.isFinite() && positionUncertaintyArcsec >= 0)
        require(frameRate.isFinite() && frameRate > 0)
        require(exposureUs > 0)
        require(pixelBytes in 1..3)
        require(legCount > 0)
        require(products.isNotEmpty())
    }

    val pathArcsec: Double get() = angularSeparationDeg(endpointA, endpointB) * 3_600.0
    val sweepRateDegPerSec: Double get() = angularSeparationDeg(endpointA, endpointB) /
        (scanDurationMs / 1_000.0)
    val framesPerLeg: Long get() = max(1L, (scanDurationMs * frameRate / 1_000.0).toLong())
    val estimatedDiskRows: Int get() = max(1, (diskCrossingMs * frameRate / 1_000.0).toInt())
    val angularStepArcsec: Double get() = pathArcsec / framesPerLeg
    val exposureBlurArcsec: Double get() = sweepRateDegPerSec * exposureUs / 1_000_000.0 * 3_600.0
    val serBytesPerLeg: Long get() {
        val value = roi.pixels.toDouble() * pixelBytes * framesPerLeg
        return saturatedLong(value)
    }
    val requiredBytes: Long get() {
        val source = serBytesPerLeg.toDouble() * legCount
        val outputPixels = roi.width.toDouble() * framesPerLeg
        val output = outputPixels * when {
            products.contains(SolarOutputProduct.LINEAR_PNG) &&
                products.contains(SolarOutputProduct.DISPLAY_PNG) -> 4.0
            products.contains(SolarOutputProduct.LINEAR_PNG) ||
                products.contains(SolarOutputProduct.DISPLAY_PNG) -> 2.0
            else -> 0.0
        } * legCount
        val fits = if (products.contains(SolarOutputProduct.LINEAR_FITS)) {
            outputPixels * 2.0 * legCount + 8.0 * 2_880.0 * legCount
        } else 0.0
        return saturatedLong(source + output + fits)
    }
    fun directionForLeg(legIndex: Int): SolarScanDirection {
        require(legIndex >= 0)
        return if (legIndex % 2 == 0) SolarScanDirection.A_TO_B else SolarScanDirection.B_TO_A
    }

    fun legCountToRun(): Int = if (continuous) legCount else legCount.coerceAtLeast(1)
}

enum class SolarScanIssueCode {
    INVALID_END,
    INVALID_DURATION,
    INVALID_CROSSING,
    INVALID_FRAME_RATE,
    TOO_MANY_FRAMES,
    INVALID_LEG_COUNT,
    INVALID_EXPOSURE,
    INVALID_ROI,
    SOURCE_TOO_SHORT,
    OUTSIDE_MARGIN_SHORT,
    EXPOSURE_BLUR,
    FRAME_RATE_LOW,
    STORAGE_LOW,
    NO_CALIBRATION,
    UNSUPPORTED_PIXEL_FORMAT
}

enum class SolarScanIssueSeverity {
    ERROR,
    WARNING
}

data class SolarScanIssue(
    val code: SolarScanIssueCode,
    val severity: SolarScanIssueSeverity,
    val actual: Double? = null,
    val required: Double? = null
)

data class SolarScanPreflight(
    val issues: List<SolarScanIssue>
) {
    val errors: List<SolarScanIssue>
        get() = issues.filter { it.severity == SolarScanIssueSeverity.ERROR }
    val warnings: List<SolarScanIssue>
        get() = issues.filter { it.severity == SolarScanIssueSeverity.WARNING }
    val canStart: Boolean get() = errors.isEmpty()
}

object SolarScanPreflightEvaluator {
    const val MIN_OUTSIDE_MARGIN_ARCSEC = 30.0
    const val MAX_EXPOSURE_BLUR_ARCSEC = 8.0
    const val MIN_DISK_FRAME_ROWS = 512
    const val STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L
    const val MAX_LEGS = 200
    const val MAX_FRAMES_PER_LEG = 100_000L

    fun evaluate(
        configuration: SolarScanConfiguration,
        availableBytes: Long,
        calibratedForCurrentMount: Boolean,
        supportedPixelBytes: Set<Int> = setOf(1, 2)
    ): SolarScanPreflight {
        val issues = mutableListOf<SolarScanIssue>()
        val separationArcsec = configuration.pathArcsec
        if (separationArcsec < 1.0) {
            issues += SolarScanIssue(
                SolarScanIssueCode.INVALID_END,
                SolarScanIssueSeverity.ERROR,
                separationArcsec,
                1.0
            )
        }
        if (configuration.scanDurationMs !in 500..600_000) {
            issues += SolarScanIssue(
                SolarScanIssueCode.INVALID_DURATION,
                SolarScanIssueSeverity.ERROR,
                configuration.scanDurationMs.toDouble(),
                600_000.0
            )
        }
        if (configuration.frameRate !in 1.0..2_000.0) {
            issues += SolarScanIssue(
                SolarScanIssueCode.INVALID_FRAME_RATE,
                SolarScanIssueSeverity.ERROR,
                configuration.frameRate,
                2_000.0
            )
        }
        if (configuration.framesPerLeg > MAX_FRAMES_PER_LEG) {
            issues += SolarScanIssue(
                SolarScanIssueCode.TOO_MANY_FRAMES,
                SolarScanIssueSeverity.ERROR,
                configuration.framesPerLeg.toDouble(),
                MAX_FRAMES_PER_LEG.toDouble()
            )
        }
        if (configuration.diskCrossingMs >= configuration.scanDurationMs) {
            issues += SolarScanIssue(
                SolarScanIssueCode.INVALID_CROSSING,
                SolarScanIssueSeverity.ERROR,
                configuration.diskCrossingMs.toDouble(),
                (configuration.scanDurationMs - 1).toDouble()
            )
        }
        if (configuration.legCount !in 1..MAX_LEGS) {
            issues += SolarScanIssue(
                SolarScanIssueCode.INVALID_LEG_COUNT,
                SolarScanIssueSeverity.ERROR,
                configuration.legCount.toDouble(),
                MAX_LEGS.toDouble()
            )
        }
        val outsideArcsec = (separationArcsec - configuration.diskDiameterArcsec) / 2.0
        val requiredMargin = max(
            MIN_OUTSIDE_MARGIN_ARCSEC,
            max(configuration.startBlankArcsec, configuration.endBlankArcsec) +
                configuration.positionUncertaintyArcsec
        )
        if (separationArcsec < configuration.diskDiameterArcsec) {
            issues += SolarScanIssue(
                SolarScanIssueCode.SOURCE_TOO_SHORT,
                SolarScanIssueSeverity.ERROR,
                separationArcsec,
                configuration.diskDiameterArcsec
            )
        } else if (outsideArcsec < requiredMargin) {
            issues += SolarScanIssue(
                SolarScanIssueCode.OUTSIDE_MARGIN_SHORT,
                SolarScanIssueSeverity.ERROR,
                outsideArcsec,
                requiredMargin
            )
        }
        val exposureLimitUs = if (configuration.sweepRateDegPerSec <= 0 || !configuration.sweepRateDegPerSec.isFinite()) {
            0L
        } else {
            floor(MAX_EXPOSURE_BLUR_ARCSEC /
                (configuration.sweepRateDegPerSec * 3_600.0) * 1_000_000.0).toLong()
        }
        if (configuration.exposureUs > exposureLimitUs) {
            issues += SolarScanIssue(
                SolarScanIssueCode.EXPOSURE_BLUR,
                SolarScanIssueSeverity.WARNING,
                configuration.exposureBlurArcsec,
                MAX_EXPOSURE_BLUR_ARCSEC
            )
        }
        if (configuration.estimatedDiskRows < MIN_DISK_FRAME_ROWS) {
            issues += SolarScanIssue(
                SolarScanIssueCode.FRAME_RATE_LOW,
                SolarScanIssueSeverity.WARNING,
                configuration.estimatedDiskRows.toDouble(),
                MIN_DISK_FRAME_ROWS.toDouble()
            )
        }
        if (configuration.pixelBytes !in supportedPixelBytes) {
            issues += SolarScanIssue(
                SolarScanIssueCode.UNSUPPORTED_PIXEL_FORMAT,
                SolarScanIssueSeverity.ERROR,
                configuration.pixelBytes.toDouble()
            )
        }
        if (configuration.roi.pixels <= 0 || configuration.roi.width.toLong() * configuration.roi.height >
            32L * 1024L * 1024L
        ) {
            issues += SolarScanIssue(
                SolarScanIssueCode.INVALID_ROI,
                SolarScanIssueSeverity.ERROR,
                configuration.roi.pixels.toDouble()
            )
        }
        val requiredWithReserve = if (configuration.requiredBytes >
            Long.MAX_VALUE - STORAGE_RESERVE_BYTES
        ) Long.MAX_VALUE else configuration.requiredBytes + STORAGE_RESERVE_BYTES
        if (availableBytes in 0 until requiredWithReserve) {
            issues += SolarScanIssue(
                SolarScanIssueCode.STORAGE_LOW,
                SolarScanIssueSeverity.ERROR,
                availableBytes.toDouble(),
                requiredWithReserve.toDouble()
            )
        }
        if (!calibratedForCurrentMount) {
            issues += SolarScanIssue(
                SolarScanIssueCode.NO_CALIBRATION,
                SolarScanIssueSeverity.WARNING
            )
        }
        return SolarScanPreflight(issues)
    }
}

data class SolarScanLegPlan(
    val number: Int,
    val direction: SolarScanDirection,
    val durationMs: Long,
    val expectedFrames: Long,
    val startEndpoint: SolarScanEndpoint,
    val endEndpoint: SolarScanEndpoint
) {
    val outputName: String
        get() = "leg-%04d-%s".format(
            number,
            when (direction) {
                SolarScanDirection.A_TO_B -> "a-to-b"
                SolarScanDirection.B_TO_A -> "b-to-a"
                SolarScanDirection.UNKNOWN -> "unknown"
            }
        )
}

fun SolarScanConfiguration.legPlans(): List<SolarScanLegPlan> =
    List(legCountToRun()) { index ->
        val direction = directionForLeg(index)
        SolarScanLegPlan(
            number = index + 1,
            direction = direction,
            durationMs = scanDurationMs,
            expectedFrames = framesPerLeg,
            startEndpoint = if (direction == SolarScanDirection.A_TO_B) endpointA else endpointB,
            endEndpoint = if (direction == SolarScanDirection.A_TO_B) endpointB else endpointA
        )
    }

data class SolarScanCoverageReport(
    val firstContactFraction: Double,
    val lastContactFraction: Double,
    val startBlankArcsec: Double,
    val endBlankArcsec: Double,
    val completeDiskCovered: Boolean,
    val accelerationOverlapsDisk: Boolean,
    val decelerationOverlapsDisk: Boolean,
    val stableCoverageFraction: Double
)

enum class SolarBandingAxis {
    OFF,
    AUTO,
    VERTICAL,
    HORIZONTAL
}

data class SolarProductIntegrity(
    val byteCount: Long,
    val sha256: String
) {
    init {
        require(byteCount > 0L)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun matches(file: File): Boolean =
        file.isFile && file.length() == byteCount && digest(file) == sha256

    companion object {
        fun of(file: File): SolarProductIntegrity {
            require(file.isFile && file.length() > 0L) { "Solar result product is missing or empty." }
            return SolarProductIntegrity(file.length(), digest(file))
        }

        private fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(64 * 1024).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

data class SolarScanCorrectionSettings(
    val bandingAxis: SolarBandingAxis = SolarBandingAxis.OFF,
    val bandingWidthPixels: Int? = null,
    val alignScanRows: Boolean = false,
    val correctPeriodicOscillation: Boolean = false
) {
    init {
        require(bandingWidthPixels == null || (bandingWidthPixels in 7..51 && bandingWidthPixels % 2 == 1)) {
            "Banding reference width must be an odd value from 7 to 51 pixels."
        }
    }
}

data class SolarFlatSettings(
    val synthetic: Boolean = false,
    val syntheticStrength: Int = 100,
    val physicalSourcePath: String? = null
) {
    init {
        require(syntheticStrength in 1..100)
        require(physicalSourcePath == null || physicalSourcePath.isNotBlank())
        require(!synthetic || physicalSourcePath == null) {
            "Synthetic and physical flat correction are mutually exclusive."
        }
    }
}

data class SolarReconstructionSettings(
    val lineShiftPixels: Double = 0.0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val rotationDegrees: Int = 0,
    val frameStride: Int = 1,
    val scanCorrection: SolarScanCorrectionSettings = SolarScanCorrectionSettings(),
    val flat: SolarFlatSettings = SolarFlatSettings(),
    val products: Set<SolarOutputProduct> = setOf(
        SolarOutputProduct.DISPLAY_PNG,
        SolarOutputProduct.LINEAR_FITS
    )
) {
    init {
        require(lineShiftPixels.isFinite())
        require(rotationDegrees % 90 == 0 && rotationDegrees in 0..270)
        require(frameStride in 1..64)
        require(products.isNotEmpty())
    }
}

data class SolarCorrectionReport(
    val geometryApplied: Boolean = false,
    val bandingApplied: SolarBandingAxis = SolarBandingAxis.OFF,
    val bandingWidthPixels: Int? = null,
    val scanRowsAligned: Boolean = false,
    val oscillationPeriodPixels: Double? = null,
    val oscillationAmplitudePixels: Double? = null,
    val syntheticFlatStrength: Int? = null,
    val physicalFlatApplied: Boolean = false
) {
    init {
        require(oscillationPeriodPixels == null || oscillationPeriodPixels.isFinite())
        require(oscillationAmplitudePixels == null || oscillationAmplitudePixels.isFinite())
        require(syntheticFlatStrength == null || syntheticFlatStrength in 1..100)
    }
}

data class SolarMemorySampleSummary(
    val headroomBytes: Long?,
    val firstSampledAtUtc: String,
    val sampleCount: Int,
    val maxIntervalMs: Long
) {
    init {
        require(headroomBytes == null || headroomBytes >= 0)
        require(sampleCount >= 0)
        require(maxIntervalMs >= 0)
    }
}

data class SolarTrace(
    val width: Int,
    val constantRow: Double,
    val linear: Double,
    val quadratic: Double,
    val illuminatedFirst: Int,
    val illuminatedLast: Int
) {
    init {
        require(width > 0)
        require(constantRow.isFinite() && linear.isFinite() && quadratic.isFinite())
        require(illuminatedFirst in 0 until width)
        require(illuminatedLast in illuminatedFirst until width)
    }

    fun rowAt(column: Int): Double {
        require(column in 0 until width)
        val x = if (width == 1) 0.0 else 2.0 * column / (width - 1) - 1.0
        return (quadratic * x + linear) * x + constantRow
    }

    fun minimumRow(includeColumns: IntRange = illuminatedFirst..illuminatedLast): Double {
        var minimum = constantRow
        for (column in includeColumns) minimum = minOf(minimum, rowAt(column))
        if (kotlin.math.abs(quadratic) > 1e-9) {
            val vertexX = (-linear / (2.0 * quadratic)).coerceIn(-1.0, 1.0)
            minimum = minOf(minimum, (quadratic * vertexX + linear) * vertexX + constantRow)
        }
        return minimum
    }

    fun maximumRow(includeColumns: IntRange = illuminatedFirst..illuminatedLast): Double {
        var maximum = constantRow
        for (column in includeColumns) maximum = maxOf(maximum, rowAt(column))
        if (kotlin.math.abs(quadratic) > 1e-9) {
            val vertexX = (-linear / (2.0 * quadratic)).coerceIn(-1.0, 1.0)
            maximum = maxOf(maximum, (quadratic * vertexX + linear) * vertexX + constantRow)
        }
        return maximum
    }
}

data class SolarSpectrumAnalysis(
    val trace: SolarTrace,
    val confidence: Double,
    val lineDepth: Double,
    val suggestedRoi: SolarRoi,
    val validColumns: Int,
    val residualPixels: Double
)

data class SolarReconstructionSummary(
    val source: String,
    val displayPng: String?,
    val linearPng: String?,
    val linearFits: String?,
    val width: Int,
    val height: Int,
    val frameStride: Int,
    val sourceFrames: Int,
    val warnings: List<String>,
    val corrections: SolarCorrectionReport = SolarCorrectionReport(),
    val memory: SolarMemorySampleSummary? = null,
    val integrity: Map<SolarOutputProduct, SolarProductIntegrity> = emptyMap(),
    val completedAtUtc: String = Instant.now().toString()
)

fun angularSeparationDeg(a: SolarScanEndpoint, b: SolarScanEndpoint): Double {
    val raA = Math.toRadians(a.raHours * 15.0)
    val raB = Math.toRadians(b.raHours * 15.0)
    val decA = Math.toRadians(a.decDegrees)
    val decB = Math.toRadians(b.decDegrees)
    val cosine = sin(decA) * sin(decB) + cos(decA) * cos(decB) * cos(raB - raA)
    return Math.toDegrees(kotlin.math.acos(cosine.coerceIn(-1.0, 1.0)))
}

fun raStepHours(start: Double, end: Double): Double {
    var step = end - start
    if (step > 12.0) step -= 24.0
    if (step < -12.0) step += 24.0
    return step
}

private fun saturatedLong(value: Double): Long = when {
    !value.isFinite() || value <= 0.0 -> 0L
    value >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
    else -> value.toLong()
}
