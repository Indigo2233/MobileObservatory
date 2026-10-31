package com.indigo.mobileobservatory.simulation

import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.mount.*
import com.indigo.mobileobservatory.polar.PolarVector3
import java.time.Instant
import kotlin.math.*

data class VirtualSkySnapshot(val center: EquatorialCoordinates, val at: Instant, val x: Double, val y: Double)

/** Shared, explicit simulation state. Motion commands affect both cameras and synthetic solutions. */
class VirtualObservatory(private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val WIDTH = 1024
        const val HEIGHT = 768
        const val SCALE_ARCSEC = 2.99
        const val FOCAL_MM = 200f
        const val PIXEL_UM = 2.9f
        const val DEFAULT_AZIMUTH_ERROR_DEG = .08
        const val DEFAULT_ALTITUDE_ERROR_DEG = .06
    }
    val site = ObserverSite(30.0, 120.0)
    @Volatile var starsVisible = true
    @Volatile var failedSolvesRemaining = 0
    @Volatile var decBacklashMs = 1800
    @Volatile var driftPxPerSecond = .005
    @Volatile var manualDegreesPerSecond = 3.0
    private var last = clock()
    private var axis = PolarVector3.fromTopocentric(TopocentricCoordinates(
        site.latitudeDeg + DEFAULT_ALTITUDE_ERROR_DEG, DEFAULT_AZIMUTH_ERROR_DEG))
    private var field = PolarVector3.fromTopocentric(TopocentricCoordinates(55.0, 25.0))
    private val origin = CoordinateTransform.topocentricToJ2000(field.toTopocentric(), Instant.ofEpochMilli(last), site, null)
    private var mountRa = EquatorialEpoch.j2000DegToJnowHours(origin.raDeg, origin.decDeg).first * 15.0
    private var mountDec = EquatorialEpoch.j2000DegToJnowHours(origin.raDeg, origin.decDeg).second
    private var moving: MountDirection? = null
    private var guide = false
    private var tracking = true
    val isTracking: Boolean @Synchronized get() = tracking
    private var previousDec: MountDirection? = null
    private var slackMs = 0.0
    private var pulseDeadline: Long? = null

    private fun advance() {
        val now = clock()
        val dt = (now - last).coerceAtLeast(0).toDouble()
        if (tracking) field = rotate(field, axis, -dt * 360.0 / 86_164_090.5)
        val activeMs = min(dt, ((pulseDeadline ?: now) - last).coerceAtLeast(0).toDouble())
        moving?.let { direction ->
            val ra = direction == MountDirection.EAST || direction == MountDirection.WEST
            val effective = if (guide && !ra) (activeMs - slackMs).coerceAtLeast(0.0) else activeMs
            if (guide && !ra) slackMs = (slackMs - activeMs).coerceAtLeast(0.0)
            val rate = if (guide) (if (ra) .004 else .006) * SCALE_ARCSEC / 3600.0 else manualDegreesPerSecond / 1000.0
            val angle = effective * rate * if (direction == MountDirection.EAST || direction == MountDirection.NORTH) 1 else -1
            if (ra) {
                val rotation = angle / if (guide) max(.15, cos(Math.toRadians(mountDec))) else 1.0
                field = rotate(field, axis, rotation)
                mountRa = normalize360(mountRa + rotation)
            } else {
                val tangent = PolarVector3.crossProduct(field, axis).unit()
                field = rotate(field, tangent, angle)
                mountDec = (mountDec + angle).coerceIn(-89.0, 89.0)
            }
        }
        if (pulseDeadline?.let { now >= it } == true) { moving = null; pulseDeadline = null }
        // Small specified tracking error makes the live guide loop observable.
        if (tracking && dt > 0) field = rotate(field, axis, driftPxPerSecond * dt / 1000 * SCALE_ARCSEC / 3600)
        last = now
    }

    @Synchronized fun snapshot(): VirtualSkySnapshot {
        advance()
        val at = Instant.ofEpochMilli(last)
        val center = CoordinateTransform.topocentricToJ2000(field.toTopocentric(), at, site, null)
        val dx = normalizeSigned180(center.raDeg - origin.raDeg) * cos(Math.toRadians(origin.decDeg)) * 3600 / SCALE_ARCSEC
        val dy = -(center.decDeg - origin.decDeg) * 3600 / SCALE_ARCSEC
        return VirtualSkySnapshot(center, at, dx, dy)
    }

    @Synchronized fun coordinates(): MountCoordinates { advance(); return MountCoordinates(mountRa / 15.0, mountDec) }
    @Synchronized fun start(direction: MountDirection, guiding: Boolean, durationMs: Int? = null) {
        advance()
        if (guiding && direction in listOf(MountDirection.NORTH, MountDirection.SOUTH) && previousDec != direction) {
            slackMs = decBacklashMs.toDouble(); previousDec = direction
        }
        guide = guiding; moving = direction; pulseDeadline = durationMs?.let { last + it }
    }
    @Synchronized fun stop() { advance(); moving = null; pulseDeadline = null }
    @Synchronized fun tracking(enabled: Boolean) { advance(); tracking = enabled }
    @Synchronized fun slew(target: MountCoordinates) {
        stop()
        val j2000 = EquatorialEpoch.jnowToJ2000(EquatorialCoordinates(target.raDeg, target.decDeg), Instant.ofEpochMilli(last))
        field = PolarVector3.fromTopocentric(CoordinateTransform.j2000ToTopocentric(j2000, Instant.ofEpochMilli(last), site, null))
        mountRa = target.raDeg; mountDec = target.decDeg
    }
    @Synchronized fun sync(target: MountCoordinates) { advance(); mountRa = target.raDeg; mountDec = target.decDeg }
    @Synchronized fun adjustPole(eastDeg: Double, upDeg: Double) {
        advance()
        val zenith = PolarVector3(0.0, 0.0, 1.0)
        val west = PolarVector3(0.0, 1.0, 0.0)
        // East increases azimuth; altitude rises with a negative rotation about west.
        axis = rotate(rotate(axis, zenith, -eastDeg), west, -upDeg)
        field = rotate(rotate(field, zenith, -eastDeg), west, -upDeg)
    }
    /** Move the axis and attached camera together when selecting a reproducible test offset. */
    @Synchronized fun setPoleError(eastDeg: Double, upDeg: Double) {
        require(eastDeg.isFinite() && upDeg.isFinite() && abs(site.latitudeDeg + upDeg) < 90)
        advance()
        val target = PolarVector3.fromTopocentric(TopocentricCoordinates(site.latitudeDeg + upDeg, eastDeg))
        val rotationAxis = PolarVector3.crossProduct(axis, target)
        if (rotationAxis.length > 1e-12) {
            val angle = Math.toDegrees(atan2(rotationAxis.length, PolarVector3.scalarProduct(axis, target)))
            field = rotate(field, rotationAxis.unit(), angle)
        }
        axis = target
    }
    @Synchronized fun poleError(): Pair<Double, Double> {
        val p = axis.toTopocentric()
        return normalizeSigned180(p.azimuthDeg) to (p.altitudeDeg - site.latitudeDeg)
    }
    @Synchronized fun consumeSolveFailure(): Boolean {
        if (!starsVisible) return true
        if (failedSolvesRemaining <= 0) return false
        failedSolvesRemaining--; return true
    }
    private fun rotate(v: PolarVector3, a: PolarVector3, degrees: Double) = PolarVector3.rotateByRodrigues(v, a, degrees).unit()
}
