package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import java.time.Instant
import java.time.ZoneId

data class AltitudeSample(val epochMillis: Long, val altitudeDeg: Double)

fun targetAltitudeCurve(
    raHours: Double,
    decDeg: Double,
    latitudeDeg: Double,
    longitudeDeg: Double,
    from: Instant,
    until: Instant,
    stepMinutes: Long = 10
): List<AltitudeSample> {
    if (!until.isAfter(from)) return emptyList()
    val site = ObserverSite(latitudeDeg, longitudeDeg)
    val equatorial = EquatorialCoordinates(raHours * 15.0, decDeg)
    val stepMillis = stepMinutes * 60_000L
    val samples = ArrayList<AltitudeSample>()
    var cursor = from.toEpochMilli()
    val end = until.toEpochMilli()
    while (cursor <= end) {
        val altitude = CoordinateTransform.j2000ToTopocentric(
            coordinates = equatorial,
            instant = Instant.ofEpochMilli(cursor),
            site = site,
            refraction = null
        ).altitudeDeg
        samples += AltitudeSample(cursor, altitude)
        cursor += stepMillis
    }
    return samples
}

fun tonightWindow(
    now: Instant,
    latitudeDeg: Double? = null,
    longitudeDeg: Double? = null,
    zone: ZoneId = ZoneId.systemDefault()
): Pair<Instant, Instant> {
    if (latitudeDeg != null && longitudeDeg != null) {
        val site = ObserverSite(latitudeDeg, longitudeDeg)
        val searchFrom = now.minusSeconds(20L * 3600L)
        val sunset = SequenceEphemeris.nextSunEvent(
            site,
            searchFrom,
            SequenceEphemeris.SUNSET_ALTITUDE,
            rising = false
        )
        val sunrise = sunset?.let {
            SequenceEphemeris.nextSunEvent(
                site,
                Instant.ofEpochMilli(it).plusSeconds(120),
                SequenceEphemeris.SUNSET_ALTITUDE,
                rising = true
            )
        }
        if (sunset != null && sunrise != null && sunrise > sunset) {
            return Instant.ofEpochMilli(sunset) to Instant.ofEpochMilli(sunrise)
        }
    }
    val local = now.atZone(zone)
    val start = local.toLocalDate().atTime(18, 0).atZone(zone).toInstant()
    val end = local.toLocalDate().plusDays(1).atTime(6, 0).atZone(zone).toInstant()
    return start to end
}
