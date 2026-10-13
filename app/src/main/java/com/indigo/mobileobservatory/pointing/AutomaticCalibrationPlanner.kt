package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.sequence.SequenceEphemeris
import java.time.Instant
import kotlin.math.*

data class AutomaticCalibrationConstraints(
    val minimumAltitudeDeg: Double = 25.0,
    val maximumAltitudeDeg: Double = 75.0,
    val maximumStepDeg: Double = 35.0,
    val minimumSeparationDeg: Double = 10.0,
    val minimumAxisSeparationDeg: Double = 20.0,
    val maximumMagnitude: Double = 3.5,
    val executionWindowMs: Long = 20 * 60_000L,
    val confirmationLifetimeMs: Long = 120_000
) {
    init {
        require(minimumAltitudeDeg in 20.0..60.0 && maximumAltitudeDeg in minimumAltitudeDeg..80.0)
        require(maximumStepDeg in 10.0..45.0 && minimumSeparationDeg in 5.0..maximumStepDeg)
        require(minimumAxisSeparationDeg in 20.0..90.0 && maximumMagnitude.isFinite())
        require(executionWindowMs in 60_000..1_800_000 && confirmationLifetimeMs in 1_000..120_000)
    }
}

data class AutomaticCalibrationTarget(val star: PhoneCatalogStar, val altitudeDeg: Double, val azimuthDeg: Double)
data class AutomaticCalibrationPlan(val targets: List<AutomaticCalibrationTarget>, val site: ObserverSite,
    val plannedUtcMs: Long, val constraints: AutomaticCalibrationConstraints) {
    /** Recheck the real current orientation when the user commits the preview. */
    fun canStart(observer: ObserverSite, instant: Instant, current: SkyDirection?): Boolean =
        observer == site && instant.toEpochMilli() - plannedUtcMs in 0..constraints.confirmationLifetimeMs &&
            AutomaticCalibrationPlanner.matches(targets.map { it.star }, observer, instant, current, constraints)
}

/** No motion or acquisition here. A null plan leaves the existing manual workflow available. */
object AutomaticCalibrationPlanner {
    fun plan(stars: List<PhoneCatalogStar>, site: ObserverSite, instant: Instant, sampleCount: Int,
             current: SkyDirection?, constraints: AutomaticCalibrationConstraints = AutomaticCalibrationConstraints()): AutomaticCalibrationPlan? {
        require(sampleCount == 2 || sampleCount == 4)
        val start = current ?: return null
        if (!validSite(site) || !darkThroughout(instant, site, constraints)) return null
        val startVector = enu(start.j2000(instant.toEpochMilli()), instant, site)
        if (!safeAltitude(startVector, constraints)) return null
        val candidates = stars.asSequence().filter {
            it.hip != null && it.raDeg.isFinite() && it.decDeg.isFinite() && it.decDeg in -90.0..90.0 &&
                it.magnitude.isFinite() && it.magnitude <= constraints.maximumMagnitude
        }.distinctBy { it.hip }.filter { star -> safeThroughout(star, instant, site, constraints) }
            .sortedWith(compareBy<PhoneCatalogStar> { it.magnitude }.thenBy { it.hip }).take(48)
            .map { star -> star to enu(SkyDirection(star.raDeg, star.decDeg), instant, site) }.toList()
        val selected = mutableListOf<Pair<PhoneCatalogStar, SkyVector>>()
        var evaluations = 0
        fun search(): Boolean {
            if (selected.size == sampleCount) return true
            for (candidate in candidates) {
                if (++evaluations > 200_000) return false
                if (selected.any { it.first.hip == candidate.first.hip || it.second.angleDeg(candidate.second) < constraints.minimumSeparationDeg }) continue
                val previous = selected.lastOrNull()?.second ?: startVector
                if (!safeStep(previous, candidate.second, constraints)) continue
                selected += candidate
                val enoughAxes = selected.size != 3 || sampleCount != 4 || spansTwoAxes(selected.map { it.second }, constraints)
                if (enoughAxes && search()) return true
                selected.removeAt(selected.lastIndex)
            }
            return false
        }
        if (!search()) return null
        return AutomaticCalibrationPlan(selected.map { (star, vector) ->
            val h = Direction3(vector.x, vector.y, vector.z).toAltAz()
            AutomaticCalibrationTarget(star, h.first, h.second)
        }, site, instant.toEpochMilli(), constraints)
    }

    internal fun matches(stars: List<PhoneCatalogStar>, site: ObserverSite, instant: Instant, current: SkyDirection?,
                         constraints: AutomaticCalibrationConstraints): Boolean {
        if (current == null || stars.size !in setOf(2, 4) || !validSite(site) || !darkThroughout(instant, site, constraints)) return false
        val vectors = stars.map { enu(SkyDirection(it.raDeg, it.decDeg), instant, site) }
        val initial = enu(current.j2000(instant.toEpochMilli()), instant, site)
        if (!safeAltitude(initial, constraints) || stars.any { !safeThroughout(it, instant, site, constraints) }) return false
        for (i in vectors.indices) {
            if (!safeStep(if (i == 0) initial else vectors[i-1], vectors[i], constraints)) return false
            for (j in 0 until i) if (vectors[i].angleDeg(vectors[j]) < constraints.minimumSeparationDeg) return false
        }
        return vectors.size != 4 || spansTwoAxes(vectors.take(3), constraints)
    }

    private fun validSite(site: ObserverSite) = site.latitudeDeg.isFinite() && site.latitudeDeg in -90.0..90.0 &&
        site.longitudeDeg.isFinite() && site.longitudeDeg in -180.0..180.0 && site.elevationMeters.isFinite()
    private fun darkThroughout(instant: Instant, site: ObserverSite, c: AutomaticCalibrationConstraints) =
        listOf(0L, c.executionWindowMs / 2, c.executionWindowMs).all {
            // Existing solar ephemeris is accurate to about a degree; use an 8° twilight margin.
            SequenceEphemeris.sunAltitudeDeg(instant.plusMillis(it), site) <= -8.0
        }
    private fun safeThroughout(star: PhoneCatalogStar, instant: Instant, site: ObserverSite, c: AutomaticCalibrationConstraints) =
        listOf(0L, c.executionWindowMs / 2, c.executionWindowMs).all {
            safeAltitude(enu(SkyDirection(star.raDeg, star.decDeg), instant.plusMillis(it), site), c)
        }
    private fun safeAltitude(vector: SkyVector, c: AutomaticCalibrationConstraints) =
        Math.toDegrees(asin(vector.z.coerceIn(-1.0, 1.0))) in c.minimumAltitudeDeg..c.maximumAltitudeDeg
    private fun safeStep(a: SkyVector, b: SkyVector, c: AutomaticCalibrationConstraints): Boolean {
        if (a.angleDeg(b) > c.maximumStepDeg) return false
        // Avoid a short great-circle shortcut over the zenith between high-altitude targets.
        return listOf(.25, .5, .75).all { fraction -> safeAltitude((a * (1-fraction) + b * fraction).unit(), c) }
    }
    private fun spansTwoAxes(v: List<SkyVector>, c: AutomaticCalibrationConstraints): Boolean {
        val first = v[0].cross(v[1]).unit(); val second = v[0].cross(v[2]).unit()
        return sqrt(first.cross(second).let { it.dot(it) }) >= sin(Math.toRadians(c.minimumAxisSeparationDeg))
    }
    private fun enu(direction: SkyDirection, instant: Instant, site: ObserverSite): SkyVector {
        val h = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(direction.raDeg, direction.decDeg), instant, site, null)
        val d = Direction3.fromAltAz(h.altitudeDeg, h.azimuthDeg)
        return SkyVector(d.east, d.north, d.up)
    }
}
