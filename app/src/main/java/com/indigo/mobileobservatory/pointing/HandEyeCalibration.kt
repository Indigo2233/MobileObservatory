package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import java.time.Instant
import kotlin.math.*

/** A photographic pose and its synchronous, full three-axis sensor pose. Neither is overwritten. */
data class HandEyeSample(
    val observation: OpticalObservation,
    val sensorPose: SkyPose,
    val sensorUtcMs: Long,
    val sensorElapsedMs: Long,
    val site: ObserverSite,
    /** Sensor restart, clock/sampling gap or coordinate-basis change starts a different session. */
    val sensorSessionId: String
) {
    internal fun validate() {
        require(observation.kind == OpticalObservationKind.PHONE_PLATE)
        require(observation.pose != null) { "Image roll is required for mirror calibration" }
        require(observation.geometry?.parity in setOf(ImageParity.NORMAL, ImageParity.MIRRORED)) {
            "Image reflection must be normalized before hand-eye calibration"
        }
        require(observation.geometry?.intrinsicsFingerprint?.let { it.isNotBlank() && it != "unknown" } == true) {
            "Known physical lens geometry is required"
        }
        require(sensorUtcMs > 0 && sensorElapsedMs >= 0)
        require(sensorSessionId.isNotBlank())
        require(observation.stamp.clockUncertaintyMs <= 250 &&
            abs(sensorElapsedMs - observation.stamp.elapsedMidpointMs) <= 250 &&
            abs((sensorUtcMs - sensorElapsedMs) -
                (observation.stamp.utcMidpointMs - observation.stamp.elapsedMidpointMs)) <= 250) {
            "No synchronous sensor attitude at the exposure midpoint"
        }
        require(site.latitudeDeg.isFinite() && site.latitudeDeg in -90.0..90.0 &&
            site.longitudeDeg.isFinite() && site.longitudeDeg in -180.0..180.0 && site.elevationMeters.isFinite())
    }
}

/**
 * R_optical = A_world * R_imu * opticalInImu, all proper right-handed rotations.
 * Image parity has already been undone by the solver; X additionally measures the physical
 * mirror/lens installation. A is session-local and is re-anchored by each fresh plate solution.
 * Only the fitter constructs a usable model, after an independent exposure has passed.
 */
class HandEyeCalibration internal constructor(
    val id: String,
    val version: Long,
    val source: OpticalIdentity,
    val geometry: CameraGeometry,
    val opticalInImu: SkyPose,
    val trainingSamples: List<HandEyeSample>,
    val validationSample: HandEyeSample,
    val fitResidualDeg: Double,
    val fitMaximumResidualDeg: Double,
    val validationResidualDeg: Double,
    val uncertaintyDeg: Double,
    val maxResidualDeg: Double,
    val minimumRotationDeg: Double,
    val minimumAxisSeparationDeg: Double,
    val invalidReason: String? = null
) {
    val validatedUtcMs get() = validationSample.observation.stamp.utcMidpointMs
    val trainingObservationIds get() = trainingSamples.map { it.observation.id }.toSet()
    val validationObservationIds get() = setOf(validationSample.observation.id)
    val valid get() = invalidReason == null

    fun appliesTo(observation: OpticalObservation): Boolean = valid && observation.identity == source &&
        observation.geometry == geometry && observation.pose != null

    fun invalidated(reason: String): HandEyeCalibration {
        require(reason.isNotBlank())
        return HandEyeCalibration(id, version, source, geometry, opticalInImu, trainingSamples,
            validationSample, fitResidualDeg, fitMaximumResidualDeg, validationResidualDeg,
            uncertaintyDeg, maxResidualDeg, minimumRotationDeg, minimumAxisSeparationDeg, reason)
    }
}

object HandEyeCalibrationFitter {
    /** At least three training exposures spanning two axes, followed by an unused fourth exposure. */
    fun fit(
        training: List<HandEyeSample>,
        validation: HandEyeSample,
        previousVersion: Long = 0,
        maxResidualDeg: Double = 0.5,
        minimumRotationDeg: Double = 5.0,
        minimumAxisSeparationDeg: Double = 20.0
    ): HandEyeCalibration {
        require(training.size in 3..32) { "Take at least three training exposures" }
        require(previousVersion in 0 until Long.MAX_VALUE)
        require(maxResidualDeg.isFinite() && maxResidualDeg in 0.001..5.0)
        require(minimumRotationDeg.isFinite() && minimumRotationDeg in 1.0..60.0)
        require(minimumAxisSeparationDeg.isFinite() && minimumAxisSeparationDeg in 10.0..90.0)
        val samples = training + validation
        samples.forEach(HandEyeSample::validate)
        val first = training.first()
        require(samples.all { it.observation.identity == first.observation.identity &&
            it.observation.geometry == first.observation.geometry }) { "Installation or lens geometry changed" }
        require(samples.all { it.site == first.site }) { "Observer site changed during calibration" }
        require(samples.all { it.sensorSessionId == first.sensorSessionId }) { "Sensor session changed during calibration; resample" }
        require(samples.all { first.observation.stamp.compatibleClock(it.observation.stamp) }) {
            "Clock changed during calibration"
        }
        require(samples.map { it.observation.id }.toSet().size == samples.size &&
            samples.map { it.observation.independentReferenceId }.toSet().size == samples.size) {
            "Verification must use an independent exposure"
        }
        require(samples.map { it.observation.stamp.elapsedMidpointMs }.toSet().size == samples.size)
        require(validation.observation.stamp.elapsedMidpointMs > training.maxOf { it.observation.stamp.elapsedMidpointMs }) {
            "Verification must be captured after training"
        }
        require(samples.maxOf { it.observation.stamp.elapsedMidpointMs } -
            samples.minOf { it.observation.stamp.elapsedMidpointMs } <= 1_800_000) { "Calibration session expired" }
        require(samples.all { it.observation.uncertaintyDeg <= maxResidualDeg }) {
            "Photographic uncertainty exceeds calibration tolerance"
        }
        require(training.all { rotationDistanceDeg(it.sensorPose, validation.sensorPose) >= minimumRotationDeg }) {
            "Move to a new orientation for independent verification"
        }

        // Earth rotation makes J2000 a different world frame at each exposure. Reducing every
        // observation separately is essential: a constant A cannot be fitted directly in J2000.
        val optical = training.map { SkyPoseFrames.j2000ToTrueEnu(requireNotNull(it.observation.pose),
            it.observation.stamp.utcMidpointMs, it.site) }
        val imu = training.map { it.sensorPose }
        val pairs = mutableListOf<Pair<RotationQuaternion, RotationQuaternion>>()
        for (i in training.indices) for (j in i + 1 until training.size) {
            val b = RotationQuaternion.fromPose(imu[i].relative(imu[j]))
            val c = RotationQuaternion.fromPose(optical[i].relative(optical[j]))
            // Quaternions at a half turn have an ambiguous sign. Other pairs still constrain X.
            if (b.angleDeg in minimumRotationDeg..170.0 && c.angleDeg in minimumRotationDeg..170.0) {
                require(abs(b.angleDeg - c.angleDeg) <= 2 * maxResidualDeg) {
                    "Sensor and optical rotations disagree"
                }
                pairs += b to c
            }
        }
        val minimumSin = sin(Math.toRadians(minimumAxisSeparationDeg))
        require(pairs.indices.any { i -> (i + 1 until pairs.size).any { j ->
            val axisA = pairs[i].first.axis(); val axisB = pairs[j].first.axis()
            axisA.cross(axisB).let { sqrt(it.dot(it)) } >= minimumSin
        } }) { "Rotate about two different axes; a single sweep cannot identify the mirror installation" }

        // B X = X C. The null vector of sum((L(qB)-R(qC))^T(...)) is qX.
        val normal = Array(4) { DoubleArray(4) }
        pairs.forEach { (b, c) ->
            val left = b.leftMatrix(); val right = c.rightMatrix()
            val difference = Array(4) { row -> DoubleArray(4) { column -> left[row][column] - right[row][column] } }
            for (r in 0..3) for (col in 0..3) for (k in 0..3)
                normal[r][col] += difference[k][r] * difference[k][col]
        }
        val eigen = symmetricEigen(normal)
        val sorted = eigen.values.indices.sortedBy { eigen.values[it] }
        val spectralGap = eigen.values[sorted[1]] - eigen.values[sorted[0]]
        require(spectralGap > 1e-4) {
            "Rotations do not sufficiently constrain the installation"
        }
        val x = RotationQuaternion.of(DoubleArray(4) { eigen.vectors[it][sorted[0]] }).toPose()
        val world = meanRotation(optical.indices.map { optical[it].apply(imu[it].apply(x).inverseRotation()) })
        val residuals = optical.indices.map { rotationDistanceDeg(world.apply(imu[it]).apply(x), optical[it]) }
        val fitMaximum = residuals.max()
        val fitRms = sqrt(residuals.sumOf { it * it } / residuals.size)
        require(fitMaximum <= maxResidualDeg) { "Training roll or attitude residual exceeds tolerance" }
        val holdoutOptical = SkyPoseFrames.j2000ToTrueEnu(requireNotNull(validation.observation.pose),
            validation.observation.stamp.utcMidpointMs, validation.site)
        val verification = rotationDistanceDeg(world.apply(validation.sensorPose).apply(x), holdoutOptical)
        require(verification <= maxResidualDeg) { "Independent image roll or attitude verification failed" }
        // Small training rotations amplify pose noise into installation error. This conservative
        // allowance is separate from the measured residuals; it is not a claimed statistical RMS.
        val installationAllowance = samples.maxOf { it.observation.uncertaintyDeg } *
            (1 + 2 * sqrt(pairs.size / spectralGap))
        return HandEyeCalibration("${first.observation.identity.opticalPathId}:imu-extrinsic",
            previousVersion + 1, first.observation.identity, requireNotNull(first.observation.geometry), x,
            training.toList(), validation, fitRms, fitMaximum, verification,
            installationAllowance + maxOf(fitMaximum, verification),
            maxResidualDeg, minimumRotationDeg, minimumAxisSeparationDeg)
    }
}

/** True (unrefracted) ENU and J2000 tangent bases; apparent-place reduction is not a linear map. */
internal object SkyPoseFrames {
    fun j2000ToTrueEnu(pose: SkyPose, utcMs: Long, site: ObserverSite): SkyPose = transform(pose) { vector ->
        val direction = vector.direction()
        val h = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(direction.raDeg, direction.decDeg),
            Instant.ofEpochMilli(utcMs), site, null)
        val enu = Direction3.fromAltAz(h.altitudeDeg, h.azimuthDeg)
        SkyVector(enu.east, enu.north, enu.up)
    }

    fun trueEnuToJ2000(pose: SkyPose, utcMs: Long, site: ObserverSite): SkyPose = transform(pose) { vector ->
        val h = Direction3(vector.x, vector.y, vector.z).toAltAz()
        val p = CoordinateTransform.topocentricToJ2000(TopocentricCoordinates(h.first, h.second),
            Instant.ofEpochMilli(utcMs), site, null)
        SkyDirection(p.raDeg, p.decDeg).vector()
    }

    private fun transform(pose: SkyPose, transform: (SkyVector) -> SkyVector): SkyPose {
        val forward = transform(pose.forward).unit()
        val tangent = transform((pose.forward + pose.up * 1e-5).unit()) -
            transform((pose.forward - pose.up * 1e-5).unit())
        val up = (tangent - forward * tangent.dot(forward)).unit()
        return SkyPose(up.cross(forward).unit(), up, forward)
    }
}

/** Sensor frame remains right handed even if the separately solved image was reflected. */
internal fun AttitudeSnapshot.imuPose(): SkyPose? = runCatching {
    val y = imageUp ?: return null
    val forward = SkyVector(opticalAxis.east, opticalAxis.north, opticalAxis.up).unit()
    val raw = SkyVector(y.east, y.north, y.up)
    val up = (raw - forward * raw.dot(forward)).unit()
    SkyPose(up.cross(forward).unit(), up, forward)
}.getOrNull()

internal fun SkyPose.inverseRotation() = SkyPose(
    SkyVector(right.x, up.x, forward.x), SkyVector(right.y, up.y, forward.y),
    SkyVector(right.z, up.z, forward.z))

/** Full SO(3) residual detects pure roll errors as well as axis errors. */
internal fun rotationDistanceDeg(first: SkyPose, second: SkyPose): Double =
    RotationQuaternion.fromPose(first.relative(second)).angleDeg

private fun meanRotation(poses: List<SkyPose>): SkyPose {
    val matrix = Array(4) { DoubleArray(4) }
    poses.map(RotationQuaternion::fromPose).forEach { q ->
        for (r in 0..3) for (c in 0..3) matrix[r][c] += q.elements[r] * q.elements[c]
    }
    val eigen = symmetricEigen(matrix)
    val column = eigen.values.indices.maxBy { eigen.values[it] }
    return RotationQuaternion.of(DoubleArray(4) { eigen.vectors[it][column] }).toPose()
}

private class RotationQuaternion private constructor(val elements: DoubleArray) {
    private val w get() = elements[0]
    private val x get() = elements[1]
    private val y get() = elements[2]
    private val z get() = elements[3]
    val angleDeg get() = Math.toDegrees(2 * atan2(sqrt(x*x + y*y + z*z), abs(w)))
    fun axis() = SkyVector(x, y, z).unit()
    fun leftMatrix() = arrayOf(doubleArrayOf(w,-x,-y,-z), doubleArrayOf(x,w,-z,y),
        doubleArrayOf(y,z,w,-x), doubleArrayOf(z,-y,x,w))
    fun rightMatrix() = arrayOf(doubleArrayOf(w,-x,-y,-z), doubleArrayOf(x,w,z,-y),
        doubleArrayOf(y,-z,w,x), doubleArrayOf(z,y,-x,w))
    fun toPose() = SkyPose(
        SkyVector(1-2*(y*y+z*z), 2*(x*y+w*z), 2*(x*z-w*y)),
        SkyVector(2*(x*y-w*z), 1-2*(x*x+z*z), 2*(y*z+w*x)),
        SkyVector(2*(x*z+w*y), 2*(y*z-w*x), 1-2*(x*x+y*y)))

    companion object {
        fun of(value: DoubleArray): RotationQuaternion {
            val norm = sqrt(value.sumOf { it * it }); require(norm > 1e-12)
            val sign = if (value[0] < 0) -1 else 1
            return RotationQuaternion(DoubleArray(4) { value[it] * sign / norm })
        }
        fun fromPose(p: SkyPose): RotationQuaternion {
            val m = arrayOf(doubleArrayOf(p.right.x,p.up.x,p.forward.x),
                doubleArrayOf(p.right.y,p.up.y,p.forward.y), doubleArrayOf(p.right.z,p.up.z,p.forward.z))
            val trace = m[0][0] + m[1][1] + m[2][2]
            val q = DoubleArray(4)
            if (trace > 0) {
                val s = sqrt(trace + 1) * 2
                q[0] = s/4; q[1] = (m[2][1]-m[1][2])/s
                q[2] = (m[0][2]-m[2][0])/s; q[3] = (m[1][0]-m[0][1])/s
            } else {
                val i = (0..2).maxBy { m[it][it] }; val j = (i+1)%3; val k = (i+2)%3
                val s = sqrt(1 + m[i][i] - m[j][j] - m[k][k]) * 2
                q[0] = (m[k][j]-m[j][k])/s; q[i+1] = s/4
                q[j+1] = (m[j][i]+m[i][j])/s; q[k+1] = (m[k][i]+m[i][k])/s
            }
            return of(q)
        }
    }
}

private data class SymmetricEigen(val values: DoubleArray, val vectors: Array<DoubleArray>)

/** Jacobi eigendecomposition for a 4x4 real symmetric matrix; no Android/native dependency. */
private fun symmetricEigen(input: Array<DoubleArray>): SymmetricEigen {
    val a = Array(4) { input[it].copyOf() }
    val v = Array(4) { r -> DoubleArray(4) { c -> if (r == c) 1.0 else 0.0 } }
    repeat(100) {
        var p = 0; var q = 1
        for (i in 0..3) for (j in i+1..3) if (abs(a[i][j]) > abs(a[p][q])) { p=i; q=j }
        if (abs(a[p][q]) < 1e-13) return SymmetricEigen(DoubleArray(4) { a[it][it] }, v)
        val angle = .5 * atan2(2*a[p][q], a[q][q]-a[p][p])
        val c = cos(angle); val s = sin(angle)
        val app = a[p][p]; val aqq = a[q][q]; val apq = a[p][q]
        for (k in 0..3) if (k != p && k != q) {
            val akp=a[k][p]; val akq=a[k][q]
            a[k][p]=c*akp-s*akq; a[p][k]=a[k][p]
            a[k][q]=s*akp+c*akq; a[q][k]=a[k][q]
        }
        a[p][p]=c*c*app-2*s*c*apq+s*s*aqq
        a[q][q]=s*s*app+2*s*c*apq+c*c*aqq; a[p][q]=0.0; a[q][p]=0.0
        for (k in 0..3) {
            val vkp=v[k][p]; val vkq=v[k][q]
            v[k][p]=c*vkp-s*vkq; v[k][q]=s*vkp+c*vkq
        }
    }
    error("Hand-eye eigensolver did not converge")
}
