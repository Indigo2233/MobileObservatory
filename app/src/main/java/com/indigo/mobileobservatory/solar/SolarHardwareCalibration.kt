package com.indigo.mobileobservatory.solar

import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.sequence.SequenceEphemeris
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong

/** Tangent-plane coordinates in arcseconds for an equatorial, north-south slit. */
internal data class SolarPoint(val west: Double, val north: Double) {
    fun endpoint(instant: Instant): SolarScanEndpoint {
        val sun = SequenceEphemeris.sunEquatorial(instant)
        val ra = sun.raDeg - west / (3600.0 * cos(Math.toRadians(sun.decDeg)))
        return SolarScanEndpoint(ra.mod(360.0) / 15.0, sun.decDeg + north / 3600.0)
    }
    companion object {
        fun from(coordinates: MountCoordinates, instant: Instant): SolarPoint {
            val sun = SequenceEphemeris.sunEquatorial(instant)
            val westDegrees = (sun.raDeg - coordinates.raDeg + 180.0).mod(360.0) - 180.0
            return SolarPoint(westDegrees * 3600.0 * cos(Math.toRadians(sun.decDeg)),
                (coordinates.decDeg - sun.decDeg) * 3600.0)
        }
    }
}

internal const val SOLAR_ENDPOINT_TOLERANCE = 45.0
internal const val SOLAR_NORTH_TOLERANCE = 30.0
internal const val SOLAR_MAX_TRIAL_MS = 12_000L
internal const val SOLAR_MAX_WEST_OFFSET = 2_100.0
internal val SOLAR_EAST_OR_WEST_START = 1_000.0..1_900.0

/** Measures source delivery intervals before the writer's queue changes their timing. */
internal class SolarFrameCadence {
    private var lastTimestamp: Long? = null
    private val timestamps = mutableListOf<Long>()
    private val baseline = mutableListOf<Long>()
    private var maximumGapNs = 0L
    private var allowedGapNs = 0L

    @Synchronized
    fun observe(deliveredAtNs: Long) {
        lastTimestamp?.let { previous ->
            val gap = deliveredAtNs - previous
            if (gap <= 0) maximumGapNs = Long.MAX_VALUE
            else {
                if (allowedGapNs == 0L && baseline.size < 120) baseline.add(gap)
                maximumGapNs = maxOf(maximumGapNs, gap)
            }
        }
        lastTimestamp = deliveredAtNs
        timestamps.add(deliveredAtNs)
    }

    @Synchronized
    fun finishBaseline() {
        check(baseline.size >= 7) { "Too few distinct frame timestamps to measure capture cadence." }
        val typical = baseline.sorted()[baseline.size / 2]
        check(typical <= TimeUnit.MILLISECONDS.toNanos(125)) {
            "The spectral capture must deliver at least 8 frames per second."
        }
        allowedGapNs = typical + typical * 3 / 4
    }

    @Synchronized
    fun checkContinuous() {
        check(allowedGapNs > 0 && maximumGapNs <= allowedGapNs) {
            "The camera frame gap exceeded the measured capture cadence."
        }
    }

    @Synchronized
    fun timing(coverage: SolarTrialCoverage): SolarSweepTiming {
        check(timestamps.size == coverage.frames && coverage.frames >= 2) {
            "The SER frame count differs from the accepted camera timestamps."
        }
        val durationNs = timestamps.last() - timestamps.first()
        val crossingNs = timestamps[coverage.endDiskFrame - 1] - timestamps[coverage.firstDiskFrame]
        return SolarSweepTiming(
            framesPerSecond = (timestamps.size - 1) * 1e9 / durationNs,
            diskCrossingMs = TimeUnit.NANOSECONDS.toMillis(crossingNs).coerceAtLeast(1)
        )
    }
}

internal data class SolarSweepTiming(val framesPerSecond: Double, val diskCrossingMs: Long)

internal data class SolarTrialCoverage(
    val frames: Int,
    val width: Int,
    val firstDiskFrame: Int,
    val endDiskFrame: Int,
    val firstColumn: Int,
    val lastColumn: Int
) {
    val leftMargin: Int get() = firstColumn
    val rightMargin: Int get() = width - 1 - lastColumn
    val hasSlitMargin: Boolean get() = minOf(leftMargin, rightMargin) >= 8
}

internal data class SolarTrialMotion(
    val direction: MountDirection,
    val start: SolarPoint,
    val end: SolarPoint,
    val motionMs: Long
) {
    private val sign: Int get() = direction.solarSign
    private val distance: Double get() = (end.west - start.west) * sign

    init {
        require(direction == MountDirection.WEST || direction == MountDirection.EAST)
        require(motionMs in 500..SOLAR_MAX_TRIAL_MS && distance in 1600.0..4000.0 &&
            -start.west * sign in SOLAR_EAST_OR_WEST_START && end.west * sign in SOLAR_EAST_OR_WEST_START &&
            abs(end.north - start.north) <= SOLAR_NORTH_TOLERANCE
        ) { "The trial must cross the disk in the selected direction with both ends outside it." }
    }

    fun matchesStart(actual: SolarPoint): Boolean =
        abs(actual.west - start.west) <= SOLAR_ENDPOINT_TOLERANCE &&
            abs(actual.north - start.north) <= SOLAR_NORTH_TOLERANCE

    fun meetsEnd(actual: SolarPoint): Boolean =
        abs(actual.west - end.west) <= SOLAR_ENDPOINT_TOLERANCE &&
            abs(actual.north - end.north) <= SOLAR_NORTH_TOLERANCE

    fun adjustedMotionMs(actualStart: SolarPoint): Long {
        require(matchesStart(actualStart))
        val distanceRemaining = (end.west - actualStart.west) * sign
        val adjusted = (motionMs * distanceRemaining / distance).roundToLong()
        check(adjusted in 500..SOLAR_MAX_TRIAL_MS) { "The corrected sweep exceeds the calibrated time limit." }
        return adjusted
    }

    fun withinSweep(actual: SolarPoint): Boolean =
        (actual.west - start.west) * sign in -SOLAR_ENDPOINT_TOLERANCE..(
            (end.west - start.west) * sign + SOLAR_ENDPOINT_TOLERANCE
        ) && abs(actual.north - start.north) <= SOLAR_NORTH_TOLERANCE

    fun pairsWith(reverse: SolarTrialMotion): Boolean =
        direction != reverse.direction && matchesStart(reverse.end) && reverse.matchesStart(end)
}

internal val MountDirection.solarSign: Int get() = when (this) {
    MountDirection.WEST -> 1
    MountDirection.EAST -> -1
    else -> error("Solar sweeps use only the RA axis.")
}

internal fun MountDirection.reverseSolar(): MountDirection =
    if (solarSign == 1) MountDirection.EAST else MountDirection.WEST
