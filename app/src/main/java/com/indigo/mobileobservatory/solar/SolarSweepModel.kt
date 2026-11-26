package com.indigo.mobileobservatory.solar

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One-dimensional solar-disk crossing for the component perpendicular to the slit.
 *
 * Distances are measured from the disk centre in arcseconds. Negative values are before
 * the disk centre and positive values are after it for either scan direction.
 */
data class SolarSweepModel(
    val diskRadiusArcsec: Double,
    val startCenterArcsec: Double,
    val endCenterArcsec: Double,
    val accelerationFraction: Double = 0.20,
    val decelerationFraction: Double = 0.20
) {
    init {
        require(diskRadiusArcsec.isFinite() && diskRadiusArcsec > 0)
        require(startCenterArcsec.isFinite() && endCenterArcsec.isFinite())
        require(startCenterArcsec != endCenterArcsec)
        require(accelerationFraction in 0.0..0.49)
        require(decelerationFraction in 0.0..0.49)
        require(accelerationFraction + decelerationFraction < 1.0)
    }

    fun centerAt(fraction: Double): Double {
        val t = fraction.coerceIn(0.0, 1.0)
        val distance = endCenterArcsec - startCenterArcsec
        val accelerationDistance = accelerationFraction / 2.0
        val stableDistance = 1.0 - accelerationFraction - decelerationFraction
        val decelerationDistance = decelerationFraction / 2.0
        val totalDistance = accelerationDistance + stableDistance + decelerationDistance
        val travelled = when {
            t < accelerationFraction -> {
                t * t / (2.0 * max(1e-9, accelerationFraction))
            }
            t <= 1.0 - decelerationFraction -> {
                accelerationDistance + t - accelerationFraction
            }
            else -> {
                val u = (t - (1.0 - decelerationFraction)) /
                    max(1e-9, decelerationFraction)
                accelerationDistance + stableDistance +
                    decelerationFraction * (u - u * u / 2.0)
            }
        }
        val travelledFraction = (travelled / totalDistance).coerceIn(0.0, 1.0)
        return startCenterArcsec + distance * travelledFraction
    }

    fun illuminatedHalfChordAt(fraction: Double): Double {
        val distance = abs(centerAt(fraction))
        if (distance >= diskRadiusArcsec) return 0.0
        return sqrt(diskRadiusArcsec * diskRadiusArcsec - distance * distance)
    }

    fun report(): SolarScanCoverageReport {
        val startDistance = abs(startCenterArcsec)
        val endDistance = abs(endCenterArcsec)
        val ascending = endCenterArcsec > startCenterArcsec
        val firstTarget = if (ascending) -diskRadiusArcsec else diskRadiusArcsec
        val lastTarget = if (ascending) diskRadiusArcsec else -diskRadiusArcsec
        val firstContact = fractionWhereCenter(firstTarget, ascending)
        val lastContact = fractionWhereCenter(lastTarget, ascending)
        val covered = if (ascending) {
            startCenterArcsec <= -diskRadiusArcsec && endCenterArcsec >= diskRadiusArcsec
        } else {
            startCenterArcsec >= diskRadiusArcsec && endCenterArcsec <= -diskRadiusArcsec
        }
        return SolarScanCoverageReport(
            firstContactFraction = firstContact,
            lastContactFraction = lastContact,
            startBlankArcsec = (startDistance - diskRadiusArcsec).coerceAtLeast(0.0),
            endBlankArcsec = (endDistance - diskRadiusArcsec).coerceAtLeast(0.0),
            completeDiskCovered = covered,
            accelerationOverlapsDisk = firstContact < accelerationFraction,
            decelerationOverlapsDisk = lastContact > 1.0 - decelerationFraction,
            stableCoverageFraction = max(
                0.0,
                min(lastContact, 1.0 - decelerationFraction) -
                    max(firstContact, accelerationFraction)
            )
        )
    }

    private fun fractionWhereCenter(target: Double, ascending: Boolean): Double {
        var low = 0.0
        var high = 1.0
        repeat(60) {
            val middle = (low + high) / 2.0
            val beforeTarget = if (ascending) centerAt(middle) < target else centerAt(middle) > target
            if (beforeTarget) low = middle else high = middle
        }
        return (low + high) / 2.0
    }
}

data class SolarSlitIllumination(
    val startPixel: Double,
    val endPixel: Double
) {
    val length: Double get() = (endPixel - startPixel).coerceAtLeast(0.0)
    val visible: Boolean get() = length > 0.0
}

/**
 * Projects a circular solar disk onto a finite slit.
 *
 * [centerOffsetPixels] is the disk centre offset along the slit. Only this value and the
 * perpendicular distance [centerDistancePixels] determine the illuminated segment.
 */
fun projectedSlitIllumination(
    centerOffsetPixels: Double,
    centerDistancePixels: Double,
    diskRadiusPixels: Double,
    slitLengthPixels: Double
): SolarSlitIllumination {
    if (diskRadiusPixels <= 0.0 || centerDistancePixels >= diskRadiusPixels) {
        return SolarSlitIllumination(0.0, 0.0)
    }
    val halfChord = sqrt(
        diskRadiusPixels * diskRadiusPixels -
            centerDistancePixels * centerDistancePixels
    )
    val start = max(0.0, centerOffsetPixels + slitLengthPixels / 2.0 - halfChord)
    val end = min(slitLengthPixels, centerOffsetPixels + slitLengthPixels / 2.0 + halfChord)
    return SolarSlitIllumination(start, max(start, end))
}
