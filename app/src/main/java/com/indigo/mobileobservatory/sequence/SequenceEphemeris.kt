package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.astro.AstroTime
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Low-precision sun and moon places for sequencer waits, conditions and the night window.
 * Good to about a degree, which is enough for twilight and altitude loops.
 */
object SequenceEphemeris {
    const val SUNSET_ALTITUDE = -0.833
    const val CIVIL_ALTITUDE = -6.0
    const val NAUTICAL_ALTITUDE = -12.0
    const val ASTRONOMICAL_ALTITUDE = -18.0

    fun sunEquatorial(instant: Instant): EquatorialCoordinates {
        val t = centuries(instant)
        val l0 = normalizeDeg(280.46646 + 36000.76983 * t + 0.0003032 * t * t)
        val m = normalizeDeg(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val mr = Math.toRadians(m)
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mr) +
            (0.019993 - 0.000101 * t) * sin(2.0 * mr) +
            0.000289 * sin(3.0 * mr)
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
        val eps = 23.439291 - 0.0130042 * t + 0.00256 * cos(Math.toRadians(omega))
        return eclipticToEquatorial(lambda, 0.0, eps)
    }

    fun moonEquatorial(instant: Instant): EquatorialCoordinates {
        val t = centuries(instant)
        val lp = normalizeDeg(218.3164477 + 481267.88123421 * t)
        val d = normalizeDeg(297.8501921 + 445267.1114034 * t)
        val m = normalizeDeg(357.5291092 + 35999.0502909 * t)
        val mp = normalizeDeg(134.9633964 + 477198.8675055 * t)
        val f = normalizeDeg(93.2720950 + 483202.0175273 * t)
        val lon = lp +
            6.289 * sinDeg(mp) +
            1.274 * sinDeg(2.0 * d - mp) +
            0.658 * sinDeg(2.0 * d) +
            0.214 * sinDeg(2.0 * mp) -
            0.186 * sinDeg(m) -
            0.114 * sinDeg(2.0 * f) +
            0.059 * sinDeg(2.0 * d - 2.0 * mp) +
            0.057 * sinDeg(2.0 * d - m - mp) +
            0.053 * sinDeg(2.0 * d + mp) +
            0.046 * sinDeg(2.0 * d - m) +
            0.041 * sinDeg(m - mp) -
            0.035 * sinDeg(d) -
            0.031 * sinDeg(m + mp)
        val lat = 5.128 * sinDeg(f) +
            0.281 * sinDeg(mp + f) +
            0.278 * sinDeg(mp - f) +
            0.173 * sinDeg(2.0 * d - f) +
            0.055 * sinDeg(2.0 * d + f - mp) +
            0.046 * sinDeg(2.0 * d - f - mp)
        val eps = 23.439291 - 0.0130042 * t
        return eclipticToEquatorial(lon, lat, eps)
    }

    fun sunAltitudeDeg(instant: Instant, site: ObserverSite): Double =
        horizontalAltitude(sunEquatorial(instant), instant, site)

    fun moonAltitudeDeg(instant: Instant, site: ObserverSite): Double =
        horizontalAltitude(moonEquatorial(instant), instant, site)

    fun moonIlluminationPct(instant: Instant): Double {
        val sun = sunEquatorial(instant)
        val moon = moonEquatorial(instant)
        val elongation = angularSeparationDeg(sun, moon)
        val fraction = (1.0 - cos(Math.toRadians(elongation))) / 2.0
        return (fraction * 100.0).coerceIn(0.0, 100.0)
    }

    fun nextSunEvent(
        site: ObserverSite,
        after: Instant,
        altitudeDeg: Double,
        rising: Boolean
    ): Long? = nextAltitudeCrossing(after, altitudeDeg, rising) { instant ->
        sunAltitudeDeg(instant, site)
    }

    fun nextMoonEvent(
        site: ObserverSite,
        after: Instant,
        altitudeDeg: Double,
        rising: Boolean
    ): Long? = nextAltitudeCrossing(after, altitudeDeg, rising) { instant ->
        moonAltitudeDeg(instant, site)
    }

    fun nextMeridianMillis(raHours: Double, site: ObserverSite, after: Instant): Long {
        val lst = localSiderealHours(site.longitudeDeg, after)
        var ha = lst - raHours
        while (ha < -12.0) ha += 24.0
        while (ha >= 12.0) ha -= 24.0
        val minutes = -ha * 60.0
        val wait = if (minutes > 0.5) minutes else minutes + 24.0 * 60.0
        return after.toEpochMilli() + (wait * 60_000.0).toLong()
    }

    fun nextProviderMillis(
        provider: String,
        afterMillis: Long,
        offsetMinutes: Int,
        latitudeDeg: Double?,
        longitudeDeg: Double?,
        raHours: Double?
    ): Long? {
        if (provider == "TimeProvider") return null
        val site = if (latitudeDeg != null && longitudeDeg != null) {
            ObserverSite(latitudeDeg, longitudeDeg)
        } else {
            null
        }
        var searchFrom = Instant.ofEpochMilli(afterMillis)
        repeat(2) {
            val event = providerEventMillis(provider, searchFrom, site, raHours) ?: return null
            val shifted = event + offsetMinutes * 60_000L
            if (shifted > afterMillis) return shifted
            searchFrom = Instant.ofEpochMilli(afterMillis + 60_000L)
        }
        return null
    }

    private fun providerEventMillis(
        provider: String,
        after: Instant,
        site: ObserverSite?,
        raHours: Double?
    ): Long? = when (provider) {
        "SunsetProvider" -> site?.let { nextSunEvent(it, after, SUNSET_ALTITUDE, rising = false) }
        "SunriseProvider" -> site?.let { nextSunEvent(it, after, SUNSET_ALTITUDE, rising = true) }
        "CivilDuskProvider" -> site?.let { nextSunEvent(it, after, CIVIL_ALTITUDE, rising = false) }
        "CivilDawnProvider" -> site?.let { nextSunEvent(it, after, CIVIL_ALTITUDE, rising = true) }
        "NauticalDuskProvider" -> site?.let { nextSunEvent(it, after, NAUTICAL_ALTITUDE, rising = false) }
        "NauticalDawnProvider" -> site?.let { nextSunEvent(it, after, NAUTICAL_ALTITUDE, rising = true) }
        "DuskProvider" -> site?.let { nextSunEvent(it, after, ASTRONOMICAL_ALTITUDE, rising = false) }
        "DawnProvider" -> site?.let { nextSunEvent(it, after, ASTRONOMICAL_ALTITUDE, rising = true) }
        "MeridianProvider" -> {
            if (site == null || raHours == null) null
            else nextMeridianMillis(raHours, site, after)
        }
        else -> null
    }

    private fun centuries(instant: Instant): Double =
        (AstroTime.julianDateTt(instant) - AstroTime.JD_J2000) / AstroTime.DAYS_PER_JULIAN_CENTURY

    private fun eclipticToEquatorial(lonDeg: Double, latDeg: Double, epsDeg: Double): EquatorialCoordinates {
        val lon = Math.toRadians(lonDeg)
        val lat = Math.toRadians(latDeg)
        val eps = Math.toRadians(epsDeg)
        val ra = atan2(
            sin(lon) * cos(eps) - tan(lat) * sin(eps),
            cos(lon)
        )
        val dec = asin(sin(lat) * cos(eps) + cos(lat) * sin(eps) * sin(lon))
        return EquatorialCoordinates(normalizeDeg(Math.toDegrees(ra)), Math.toDegrees(dec))
    }

    private fun horizontalAltitude(
        equatorial: EquatorialCoordinates,
        instant: Instant,
        site: ObserverSite
    ): Double {
        val lst = localSiderealHours(site.longitudeDeg, instant)
        var haHours = lst - equatorial.raDeg / 15.0
        while (haHours < -12.0) haHours += 24.0
        while (haHours >= 12.0) haHours -= 24.0
        val ha = Math.toRadians(haHours * 15.0)
        val lat = Math.toRadians(site.latitudeDeg)
        val dec = Math.toRadians(equatorial.decDeg)
        return Math.toDegrees(asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)))
    }

    private fun localSiderealHours(longitudeDeg: Double, instant: Instant): Double {
        val gmstHours = Math.toDegrees(AstroTime.greenwichMeanSiderealTime(instant)) / 15.0
        return (gmstHours + longitudeDeg / 15.0).mod(24.0)
    }

    private fun nextAltitudeCrossing(
        after: Instant,
        altitudeDeg: Double,
        rising: Boolean,
        sample: (Instant) -> Double
    ): Long? {
        val start = after.toEpochMilli()
        val end = start + 36L * 60L * 60L * 1000L
        val step = 5L * 60L * 1000L
        var previousTime = start
        var previousAlt = sample(after)
        var cursor = start + step
        while (cursor <= end) {
            val instant = Instant.ofEpochMilli(cursor)
            val altitude = sample(instant)
            val crossed = if (rising) {
                previousAlt < altitudeDeg && altitude >= altitudeDeg
            } else {
                previousAlt > altitudeDeg && altitude <= altitudeDeg
            }
            if (crossed) return refineCrossing(previousTime, cursor, altitudeDeg, rising, sample)
            previousTime = cursor
            previousAlt = altitude
            cursor += step
        }
        return null
    }

    private fun refineCrossing(
        fromMillis: Long,
        toMillis: Long,
        altitudeDeg: Double,
        rising: Boolean,
        sample: (Instant) -> Double
    ): Long {
        var lo = fromMillis
        var hi = toMillis
        repeat(18) {
            val mid = (lo + hi) / 2L
            val altitude = sample(Instant.ofEpochMilli(mid))
            val before = if (rising) altitude < altitudeDeg else altitude > altitudeDeg
            if (before) lo = mid else hi = mid
        }
        return hi
    }

    private fun angularSeparationDeg(a: EquatorialCoordinates, b: EquatorialCoordinates): Double {
        val ra1 = Math.toRadians(a.raDeg)
        val dec1 = Math.toRadians(a.decDeg)
        val ra2 = Math.toRadians(b.raDeg)
        val dec2 = Math.toRadians(b.decDeg)
        val cosSep = sin(dec1) * sin(dec2) + cos(dec1) * cos(dec2) * cos(ra1 - ra2)
        return Math.toDegrees(kotlin.math.acos(cosSep.coerceIn(-1.0, 1.0)))
    }

    private fun sinDeg(degrees: Double): Double = sin(Math.toRadians(degrees))

    private fun normalizeDeg(value: Double): Double {
        var deg = value % 360.0
        if (deg < 0) deg += 360.0
        return deg
    }
}
