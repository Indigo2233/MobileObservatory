package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.EquatorialEpoch
import java.time.Instant
import kotlin.math.*

/** Units are degrees throughout this boundary. Mount hours are converted by its adapter. */
enum class SkyEpoch { J2000, JNOW }
enum class OpticalObservationKind { PHONE_PLATE, MAIN_PLATE, AUXILIARY_PLATE, CENTERED_STAR, MOUNT_MODEL }
enum class ImageParity { NORMAL, MIRRORED, UNKNOWN }
enum class RelationshipModel { AXIS_ONLY, FULL_POSE }

data class SkyDirection(val raDeg: Double, val decDeg: Double, val epoch: SkyEpoch = SkyEpoch.J2000) {
    init { require(raDeg.isFinite() && decDeg.isFinite() && decDeg in -90.0..90.0) }
    fun j2000(utcMs: Long): SkyDirection = if (epoch == SkyEpoch.J2000) this else {
        val p = EquatorialEpoch.jnowToJ2000(EquatorialCoordinates(raDeg, decDeg), Instant.ofEpochMilli(utcMs))
        SkyDirection(p.raDeg, p.decDeg)
    }
    fun vector(): SkyVector {
        require(epoch == SkyEpoch.J2000) { "Normalize epoch at observation time first" }
        val ra = Math.toRadians(raDeg); val dec = Math.toRadians(decDeg)
        return SkyVector(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
    }
}

data class SkyVector(val x: Double, val y: Double, val z: Double) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
    operator fun plus(v: SkyVector) = SkyVector(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: SkyVector) = SkyVector(x - v.x, y - v.y, z - v.z)
    operator fun times(s: Double) = SkyVector(x * s, y * s, z * s)
    fun dot(v: SkyVector) = x * v.x + y * v.y + z * v.z
    fun cross(v: SkyVector) = SkyVector(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x)
    fun unit(): SkyVector { val n = sqrt(dot(this)); require(n > 1e-12); return this * (1.0 / n) }
    fun angleDeg(v: SkyVector) = Math.toDegrees(atan2(sqrt(cross(v).let { it.dot(it) }), dot(v)))
    fun direction(): SkyDirection { val n = unit(); return SkyDirection((Math.toDegrees(atan2(n.y, n.x)) + 360) % 360, Math.toDegrees(asin(n.z.coerceIn(-1.0, 1.0)))) }
}

/** Right-handed camera basis, after undoing image reflection. A quaternion cannot undo a mirror. */
data class SkyPose(val right: SkyVector, val up: SkyVector, val forward: SkyVector) {
    init {
        require(listOf(right, up, forward).all { abs(it.dot(it) - 1) < 1e-5 })
        require(abs(right.dot(up)) < 1e-5 && right.cross(up).dot(forward) > .99999)
    }
    fun project(local: SkyVector) = (right * local.x + up * local.y + forward * local.z).unit()
    fun unproject(world: SkyVector) = SkyVector(right.dot(world), up.dot(world), forward.dot(world)).unit()
    fun relative(other: SkyPose) = SkyPose(unproject(other.right), unproject(other.up), unproject(other.forward))
    fun apply(local: SkyPose) = SkyPose(project(local.right), project(local.up), project(local.forward))
    companion object {
        fun fromSolution(direction: SkyDirection, imageUpPositionAngleDeg: Double): SkyPose {
            val f = direction.vector(); val ra = Math.toRadians(direction.raDeg); val dec = Math.toRadians(direction.decDeg)
            val north = SkyVector(-cos(ra) * sin(dec), -sin(ra) * sin(dec), cos(dec))
            val east = SkyVector(-sin(ra), cos(ra), 0.0)
            val a = Math.toRadians(imageUpPositionAngleDeg)
            val up = (north * cos(a) + east * sin(a)).unit()
            return SkyPose(up.cross(f).unit(), up, f)
        }
    }
}

/** elapsed time is Android elapsedRealtime, never uptime or a wall-clock-derived propagation age. */
data class ObservationStamp(val utcMidpointMs: Long, val elapsedMidpointMs: Long,
                            val exposureDurationMs: Long, val clockUncertaintyMs: Long) {
    init { require(utcMidpointMs > 0 && elapsedMidpointMs >= 0 && exposureDurationMs >= 0 && clockUncertaintyMs >= 0) }
    fun compatibleClock(other: ObservationStamp, maxDiscontinuityMs: Long = 250) =
        abs((utcMidpointMs - elapsedMidpointMs) - (other.utcMidpointMs - other.elapsedMidpointMs)) <=
            maxDiscontinuityMs
}

/** Changing the display orientation is intentionally not an installation/optics change. */
data class OpticalIdentity(val deviceId: String, val physicalLensId: String, val opticalPathId: String,
                           val installationRevision: Long = 0, val opticsRevision: Long = 0) {
    init { require(deviceId.isNotBlank() && physicalLensId.isNotBlank() && opticalPathId.isNotBlank()) }
}

data class CameraGeometry(val width: Int, val height: Int, val cropLeft: Int = 0, val cropTop: Int = 0,
                          val cropWidth: Int = width, val cropHeight: Int = height,
                          val parity: ImageParity = ImageParity.UNKNOWN,
                          val axisPixelX: Double = (width - 1) / 2.0, val axisPixelY: Double = (height - 1) / 2.0,
                          val intrinsicsFingerprint: String = "unknown", val roiSensorScale: Int = 1) {
    init { require(width > 0 && height > 0 && cropWidth > 0 && cropHeight > 0 && axisPixelX.isFinite() && axisPixelY.isFinite() && roiSensorScale > 0) }
}

data class OpticalObservation(val id: String, val identity: OpticalIdentity, val stamp: ObservationStamp,
    val direction: SkyDirection, val pose: SkyPose?, val geometry: CameraGeometry?,
    val uncertaintyDeg: Double, val kind: OpticalObservationKind, val independentReferenceId: String = id,
    /** Acquisition sensor is distinct from the optical-axis endpoint (e.g. telescope primary). */
    val acquisitionDeviceId: String = identity.deviceId,
    val plateScaleDegPerPixel: Double? = null) {
    init {
        require(id.isNotBlank() && independentReferenceId.isNotBlank())
        require(direction.epoch == SkyEpoch.J2000 && uncertaintyDeg.isFinite() && uncertaintyDeg > 0)
        require(plateScaleDegPerPixel == null || plateScaleDegPerPixel.isFinite() && plateScaleDegPerPixel > 0)
        require(pose == null || pose.forward.angleDeg(direction.vector()) < 1e-5)
    }
}

data class PointingEstimate(val direction: SkyDirection, val opticalPathId: String, val absoluteUtcMs: Long,
    val estimateUtcMs: Long, val uncertaintyDeg: Double, val relationshipId: String?, val relationshipVersion: Long?,
    val source: OpticalObservationKind, val observationIds: Set<String>, val propagated: Boolean,
    val imageUpPositionAngleDeg: Double? = null)

enum class AxisReadingKind { NONE, MOTOR_COUNTER, AUXILIARY_ENCODER, ABSOLUTE_ENCODER }
data class PointingMountCapabilities(val readCoordinates: Boolean = false, val goto: Boolean = false,
    val sync: Boolean = false, val tracking: Boolean = false, val manualMove: Boolean = false,
    val axisReading: AxisReadingKind = AxisReadingKind.NONE, val observesReleasedClutch: Boolean = false,
    val home: Boolean = false, val setHome: Boolean = false, val readSite: Boolean = false,
    val writeSite: Boolean = false, val readTime: Boolean = false, val writeTime: Boolean = false,
    val siteUsesLocalConfiguration: Boolean = false)
data class MountModelRevision(val connection: Long, val model: Long, val zero: Long, val mode: String)
data class MountAxisObservation(val axis1Deg: Double, val axis2Deg: Double, val elapsedMs: Long,
    val resolutionDeg: Double, val kind: AxisReadingKind, val revision: MountModelRevision) {
    fun deltaFrom(previous: MountAxisObservation): Pair<Double, Double>? {
        if (revision != previous.revision || kind != previous.kind || kind == AxisReadingKind.NONE ||
            elapsedMs <= previous.elapsedMs || !axis1Deg.isFinite() || !axis2Deg.isFinite()) return null
        return ((axis1Deg - previous.axis1Deg + 540) % 360 - 180) to ((axis2Deg - previous.axis2Deg + 540) % 360 - 180)
    }
}
