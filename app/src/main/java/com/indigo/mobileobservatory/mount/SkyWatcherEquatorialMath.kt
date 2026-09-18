package com.indigo.mobileobservatory.mount

import com.indigo.mobileobservatory.astro.AstroTime
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

internal data class SkyWatcherAxisGeometry(
    val totalSteps: Long,
    val timerFreq: Long,
    val highSpeedRatio: Long
)

internal data class SkyWatcherMountGeometry(
    val ra: SkyWatcherAxisGeometry,
    val dec: SkyWatcherAxisGeometry
) {
    val raHomePosition: Long = SkyWatcherMotorCodec.POSITION_OFFSET
    val decHomePosition: Long = SkyWatcherMotorCodec.POSITION_OFFSET + dec.totalSteps / 4
    val raZeroPos: Long = raHomePosition - ra.totalSteps / 4
    val decZeroPos: Long = decHomePosition - dec.totalSteps / 4
}

internal enum class SkyWatcherPierSide { EAST, WEST }

internal data class SkyWatcherHourAngleDec(
    val hourAngleHours: Double,
    val decDeg: Double,
    val pierSide: SkyWatcherPierSide
)

internal data class SkyWatcherAltAz(
    val azimuthDeg: Double,
    val altitudeDeg: Double
)

internal data class SkyWatcherEncoderTarget(
    val raSteps: Long,
    val decSteps: Long
)

/**
 * Polar-mode encoder ↔ sky conversion used by INDIGO's SynScan driver.
 *
 * Home (CW down, pointing at the pole) maps to HA = +6h and Dec = +90° in the
 * northern hemisphere. Southern-hemisphere sense is reversed in the same way.
 */
internal object SkyWatcherEquatorialMath {
    const val SIDEREAL_ARCSEC_PER_SEC = 15.04106864

    fun localSiderealHours(longitudeDeg: Double, instant: Instant): Double {
        val gmstHours = Math.toDegrees(AstroTime.greenwichMeanSiderealTime(instant)) / 15.0
        return (gmstHours + longitudeDeg / 15.0).mod(24.0)
    }

    fun raHours(lstHours: Double, hourAngleHours: Double): Double =
        (lstHours - hourAngleHours).mod(24.0)

    fun hourAngleHours(lstHours: Double, raHours: Double): Double {
        var ha = lstHours - raHours
        while (ha < -12.0) ha += 24.0
        while (ha >= 12.0) ha -= 24.0
        return ha
    }

    fun stepsToUnit(steps: Long, zeroPos: Long, totalSteps: Long): Double {
        require(totalSteps > 0) { "Axis step count is invalid." }
        var position = (steps - zeroPos).toDouble() / totalSteps.toDouble()
        if (position < 0.0) position += 1.0
        return position
    }

    fun unitToSteps(position: Double, zeroPos: Long, totalSteps: Long): Long {
        var normalized = position
        if (normalized > 0.75) normalized -= 1.0
        return (zeroPos + (normalized * totalSteps)).roundToLong()
    }

    fun encoderToEq(
        geometry: SkyWatcherMountGeometry,
        raSteps: Long,
        decSteps: Long,
        southernHemisphere: Boolean
    ): SkyWatcherHourAngleDec {
        val haEnc = stepsToUnit(raSteps, geometry.raZeroPos, geometry.ra.totalSteps)
        val decEnc = stepsToUnit(decSteps, geometry.decZeroPos, geometry.dec.totalSteps)
        return encoderUnitsToEq(haEnc, decEnc, southernHemisphere)
    }

    fun encoderUnitsToEq(
        haEnc: Double,
        decEnc: Double,
        southernHemisphere: Boolean
    ): SkyWatcherHourAngleDec {
        var epDec = if (decEnc < 0.0) decEnc + 1.0 else decEnc
        var west = false
        val decTurns = when {
            epDec < 0.25 -> epDec
            epDec < 0.75 -> {
                west = true
                0.5 - epDec
            }
            else -> epDec - 1.0
        }
        var dec = decTurns
        if (southernHemisphere) {
            west = !west
            dec = -dec
        }

        var ha = if (!southernHemisphere) {
            when {
                !west -> haEnc - 0.5
                haEnc < 0.5 -> haEnc
                else -> haEnc - 1.0
            }
        } else {
            when {
                west -> 0.5 - haEnc
                haEnc < 0.5 -> -haEnc
                else -> 1.0 - haEnc
            }
        }
        if (ha < -0.5) ha += 1.0
        if (ha >= 0.5) ha -= 1.0

        return SkyWatcherHourAngleDec(
            hourAngleHours = ha * 24.0,
            decDeg = dec * 360.0,
            pierSide = if (west) SkyWatcherPierSide.WEST else SkyWatcherPierSide.EAST
        )
    }

    fun eqToEncoder(
        geometry: SkyWatcherMountGeometry,
        hourAngleHours: Double,
        decDeg: Double,
        southernHemisphere: Boolean
    ): SkyWatcherEncoderTarget {
        val solutions = eqToEncoderSolutions(hourAngleHours, decDeg, southernHemisphere)
        val index = if (solutions[0].first <= 0.5) 0 else 1
        val (haUnit, decUnit) = solutions[index]
        return SkyWatcherEncoderTarget(
            raSteps = unitToSteps(haUnit, geometry.raZeroPos, geometry.ra.totalSteps),
            decSteps = unitToSteps(decUnit, geometry.decZeroPos, geometry.dec.totalSteps)
        )
    }

    fun eqToEncoderSolutions(
        hourAngleHours: Double,
        decDeg: Double,
        southernHemisphere: Boolean
    ): List<Pair<Double, Double>> {
        val ha = hourAngleHours / 24.0 * 2.0 * PI
        val dec = decDeg / 180.0 * PI
        var degWest = PI - dec
        if (southernHemisphere) {
            degWest = if (dec < 0) -dec else (2.0 * PI - dec)
        }
        var degEast = PI + dec
        if (!southernHemisphere) {
            degEast = if (dec < 0) (2.0 * PI + dec) else dec
        }
        if (degWest > PI + PI / 2.0) degWest -= 2.0 * PI
        if (degEast > PI + PI / 2.0) degEast -= 2.0 * PI
        degWest /= 2.0 * PI
        degEast /= 2.0 * PI

        var wrappedHa = ha
        if (wrappedHa > PI) wrappedHa -= 2.0 * PI
        if (wrappedHa < -PI) wrappedHa += 2.0 * PI

        var haWest = if (wrappedHa >= 0) wrappedHa else wrappedHa + 2.0 * PI
        if (southernHemisphere) haWest = PI - wrappedHa
        var haEast = wrappedHa + PI
        if (southernHemisphere) {
            haEast = if (wrappedHa >= 0) (2.0 * PI - wrappedHa) else -wrappedHa
        }
        haWest /= 2.0 * PI
        haEast /= 2.0 * PI
        return listOf(haWest to degWest, haEast to degEast)
    }

    fun rateCode(arcsecPerSec: Double, geometry: SkyWatcherAxisGeometry): Pair<Boolean, Long> {
        val speed = abs(arcsecPerSec)
        require(speed > 0.0) { "Slew rate must be positive." }
        var working = speed
        var turbo = false
        if (working > 128.0 * SIDEREAL_ARCSEC_PER_SEC) {
            turbo = true
            working /= geometry.highSpeedRatio.coerceAtLeast(1).toDouble()
        }
        working *= geometry.totalSteps.toDouble()
        working /= 3600.0 * 360.0
        val code = (geometry.timerFreq.toDouble() / working).roundToLong().coerceAtLeast(1L)
        return turbo to code
    }

    fun encoderToAltAz(
        geometry: SkyWatcherMountGeometry,
        azSteps: Long,
        altSteps: Long
    ): SkyWatcherAltAz {
        val azimuth = normalize360(stepsToDegrees(azSteps, geometry.ra.totalSteps))
        var altitude = stepsToDegrees(altSteps, geometry.dec.totalSteps)
        if (altitude > 180.0) altitude -= 360.0
        return SkyWatcherAltAz(azimuth, altitude)
    }

    fun altAzToEncoder(
        geometry: SkyWatcherMountGeometry,
        altAz: SkyWatcherAltAz
    ): SkyWatcherEncoderTarget {
        var altitude = altAz.altitudeDeg
        var azimuth = normalize360(altAz.azimuthDeg)
        if (altitude > 90.0) {
            altitude = 180.0 - altitude
            azimuth = normalize360(azimuth + 180.0)
        }
        return SkyWatcherEncoderTarget(
            raSteps = degreesToSteps(azimuth, geometry.ra.totalSteps, wrap = true),
            decSteps = degreesToSteps(altitude, geometry.dec.totalSteps, wrap = false)
        )
    }

    fun eqToHorizontal(
        hourAngleHours: Double,
        decDeg: Double,
        latitudeDeg: Double
    ): SkyWatcherAltAz {
        val hourAngle = Math.toRadians(hourAngleHours * 15.0)
        val declination = Math.toRadians(decDeg)
        val latitude = Math.toRadians(latitudeDeg)
        val sinAltitude = sin(declination) * sin(latitude) +
            cos(declination) * cos(latitude) * cos(hourAngle)
        val altitude = asin(sinAltitude.coerceIn(-1.0, 1.0))
        val azimuth = atan2(
            -sin(hourAngle) * cos(declination),
            sin(declination) * cos(latitude) -
                cos(declination) * sin(latitude) * cos(hourAngle)
        )
        return SkyWatcherAltAz(
            azimuthDeg = normalize360(Math.toDegrees(azimuth)),
            altitudeDeg = Math.toDegrees(altitude)
        )
    }

    fun horizontalToEq(
        altAz: SkyWatcherAltAz,
        latitudeDeg: Double
    ): Pair<Double, Double> {
        val altitude = Math.toRadians(altAz.altitudeDeg)
        val azimuth = Math.toRadians(altAz.azimuthDeg)
        val latitude = Math.toRadians(latitudeDeg)
        val sinDeclination = sin(altitude) * sin(latitude) +
            cos(altitude) * cos(latitude) * cos(azimuth)
        val declination = asin(sinDeclination.coerceIn(-1.0, 1.0))
        val hourAngle = atan2(
            -sin(azimuth) * cos(altitude),
            sin(altitude) * cos(latitude) -
                cos(altitude) * sin(latitude) * cos(azimuth)
        )
        var haHours = Math.toDegrees(hourAngle) / 15.0
        while (haHours < -12.0) haHours += 24.0
        while (haHours >= 12.0) haHours -= 24.0
        return haHours to Math.toDegrees(declination)
    }

    fun altazTrackingRatesArcsecPerSec(
        raHours: Double,
        decDeg: Double,
        latitudeDeg: Double,
        longitudeDeg: Double,
        instant: Instant,
        deltaSeconds: Double = 2.0
    ): Pair<Double, Double> {
        val later = instant.plusMillis((deltaSeconds * 1000.0).toLong())
        val first = eqToHorizontal(
            hourAngleHours(localSiderealHours(longitudeDeg, instant), raHours),
            decDeg,
            latitudeDeg
        )
        val second = eqToHorizontal(
            hourAngleHours(localSiderealHours(longitudeDeg, later), raHours),
            decDeg,
            latitudeDeg
        )
        val azimuthRate = wrap180(second.azimuthDeg - first.azimuthDeg) / deltaSeconds * 3600.0
        val altitudeRate = (second.altitudeDeg - first.altitudeDeg) / deltaSeconds * 3600.0
        return azimuthRate.coerceIn(-12_000.0, 12_000.0) to altitudeRate.coerceIn(-12_000.0, 12_000.0)
    }

    private fun stepsToDegrees(steps: Long, totalSteps: Long): Double {
        require(totalSteps > 0) { "Axis step count is invalid." }
        return (steps - SkyWatcherMotorCodec.POSITION_OFFSET).toDouble() / totalSteps * 360.0
    }

    private fun degreesToSteps(degrees: Double, totalSteps: Long, wrap: Boolean): Long {
        require(totalSteps > 0) { "Axis step count is invalid." }
        val value = if (wrap) normalize360(degrees) else degrees
        return SkyWatcherMotorCodec.POSITION_OFFSET + (value / 360.0 * totalSteps).roundToLong()
    }

    private fun normalize360(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    private fun wrap180(value: Double): Double {
        var wrapped = value
        while (wrapped > 180.0) wrapped -= 360.0
        while (wrapped < -180.0) wrapped += 360.0
        return wrapped
    }
}
