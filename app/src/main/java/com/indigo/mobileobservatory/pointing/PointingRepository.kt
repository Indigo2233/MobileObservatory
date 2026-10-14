package com.indigo.mobileobservatory.pointing

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A failed fit never enters this store. Absolute observations/arrival status are session-only. */
class PointingRepository(context: Context) {
    private val directory = File(context.filesDir, "pointing").also { it.mkdirs() }
    private val file = AtomicFile(File(directory, "relationships-v1.json"))
    private val _relationships = MutableStateFlow(load())
    val relationships = _relationships.asStateFlow()
    private val _handEyes = MutableStateFlow(loadHandEyes())
    val handEyes = _handEyes.asStateFlow()
    private val _estimate = MutableStateFlow<PointingEstimate?>(null)
    val estimate = _estimate.asStateFlow()
    private val _directObservations = MutableStateFlow<List<OpticalObservation>>(emptyList())
    val directObservations = _directObservations.asStateFlow()
    private val pathRevisions = mutableMapOf<String, Long>()
    private val connectedCameras = mutableMapOf<String, String>()
    private var directionRevision = 0L

    data class CaptureRevision(val path: String, val pathRevision: Long, val directionRevision: Long)
    @Synchronized fun captureRevision(path: String) = CaptureRevision(path, pathRevisions[path] ?: 0, directionRevision)
    @Synchronized fun isCurrent(revision: CaptureRevision) = revision == captureRevision(revision.path)
    @Synchronized fun isSameInstallation(revision: CaptureRevision) =
        (pathRevisions[revision.path] ?: 0) == revision.pathRevision

    @Synchronized fun recordDirectObservation(observation: OpticalObservation, revision: CaptureRevision = captureRevision(observation.identity.opticalPathId)) {
        require(observation.kind in setOf(OpticalObservationKind.MAIN_PLATE, OpticalObservationKind.AUXILIARY_PLATE))
        check(revision.path == observation.identity.opticalPathId && isCurrent(revision)) { "Optical configuration changed during sampling" }
        _directObservations.value = (_directObservations.value.filterNot {
            it.identity == observation.identity && it.acquisitionDeviceId == observation.acquisitionDeviceId
        } + observation).takeLast(8)
    }

    @Synchronized fun invalidateCurrentDirections() {
        directionRevision++
        _estimate.value = null; _directObservations.value = emptyList()
    }

    @Synchronized fun clearOpticalPathDirection(path: String) {
        pathRevisions[path] = (pathRevisions[path] ?: 0) + 1
        _directObservations.value = _directObservations.value.filterNot { it.identity.opticalPathId == path }
        val estimate = _estimate.value
        if (estimate != null && (estimate.opticalPathId == path || _relationships.value.any {
            it.id == estimate.relationshipId && it.source.opticalPathId == path
        })) _estimate.value = null
    }

    /** A transient disconnect is not a new installation; a different actual sensor is. */
    @Synchronized fun observeConnectedCamera(opticalPathId: String, acquisitionDeviceId: String) {
        val previousCamera = connectedCameras.put(opticalPathId, acquisitionDeviceId)
        if ((previousCamera != null && previousCamera != acquisitionDeviceId) || _directObservations.value.any {
            it.identity.opticalPathId == opticalPathId && it.acquisitionDeviceId != acquisitionDeviceId
        }) clearOpticalPathDirection(opticalPathId)
        val changed = _relationships.value.any { it.valid && it.target.opticalPathId == opticalPathId &&
            it.targetAcquisitionDeviceId != null && it.targetAcquisitionDeviceId != acquisitionDeviceId }
        if (changed) {
            persist(_relationships.value.map { value -> if (value.target.opticalPathId == opticalPathId &&
                value.targetAcquisitionDeviceId != null && value.targetAcquisitionDeviceId != acquisitionDeviceId)
                value.copy(invalidReason = "reference_camera_changed") else value })
            clearOpticalPathDirection(opticalPathId)
        }
    }

    @Synchronized fun saveValidated(value: OpticalCalibration, pairs: List<CalibrationPair>, handEye: HandEyeCalibration? = null,
        expectedRevisions: List<CaptureRevision> = emptyList()) {
        check(expectedRevisions.all(::isSameInstallation)) { "Optical installation changed during calibration" }
        require(value.valid && value.residualDeg.isFinite() && value.uncertaintyDeg > 0)
        require(pairs.size == 2)
        pairs.forEach { it.validate() }
        require(setOf(pairs[0].camera.id, pairs[0].reference.id) == value.trainingObservationIds &&
            setOf(pairs[1].camera.id, pairs[1].reference.id) == value.validationObservationIds)
        val key: (OpticalCalibration) -> Boolean = { it.source == value.source && it.target == value.target }
        val previous = _relationships.value.firstOrNull(key)
        require(previous == null || value.version > previous.version)
        val nextHandEyes = if (handEye == null) _handEyes.value else {
            require(handEye.valid && handEye.appliesTo(pairs.last().camera))
            val previousHandEye = _handEyes.value.firstOrNull { it.source == handEye.source && it.geometry == handEye.geometry }
            require(previousHandEye == null || handEye.version > previousHandEye.version)
            _handEyes.value.filterNot { it.source == handEye.source && it.geometry == handEye.geometry } + handEye
        }
        val evidence = AtomicFile(File(directory, "evidence-${value.validatedUtcMs}-${value.id.hashCode()}-${value.version}.json"))
        val output = evidence.startWrite()
        try {
            output.write(JSONObject().put("schema", 1).put("relationship", CalibrationJson.encode(value))
                .put("handEye", handEye?.let(HandEyeCalibrationJson::encode))
                .put("pairs", JSONArray(pairs.map { pair -> JSONObject()
                    .put("camera", CalibrationJson.observation(pair.camera)).put("reference", CalibrationJson.observation(pair.reference))
                    .put("pairingUncertaintyDeg", pair.pairingUncertaintyDeg).put("measuredPairMotionDeg", pair.measuredPairMotionDeg)
                    .put("referenceAtCameraRaDeg", pair.referenceDirection.raDeg).put("referenceAtCameraDecDeg", pair.referenceDirection.decDeg)
                    .put("opticalStability", pair.opticalStability?.let { stability -> JSONObject()
                        .put("motionUpperBoundDeg", stability.motionUpperBoundDeg)
                        .put("trackingSky", stability.trackingSky)
                        .put("site", stability.site?.let { JSONObject().put("latitudeDeg", it.latitudeDeg)
                            .put("longitudeDeg", it.longitudeDeg).put("elevationMeters", it.elevationMeters) })
                        .put("solvedSamples", JSONArray(stability.solvedSamples.map { sample -> JSONObject()
                            .put("observation", CalibrationJson.observation(sample.observation))
                            .put("captureStartElapsedMs", sample.captureStartElapsedMs).put("captureEndElapsedMs", sample.captureEndElapsedMs)
                            .put("earliestExposureElapsedMs", sample.earliestExposureElapsedMs)
                            .put("captureGeneration", sample.captureGeneration).put("frameId", sample.frameId).put("psf", psfJson(sample.stars)) }))
                        .put("frames", JSONArray(stability.frameSamples.map { sample -> JSONObject()
                            .put("frameId", sample.frameId).put("startElapsedMs", sample.startElapsedMs).put("endElapsedMs", sample.endElapsedMs)
                            .put("earliestExposureElapsedMs", sample.earliestExposureElapsedMs)
                            .put("exposureDurationMs", sample.exposureDurationMs).put("captureGeneration", sample.captureGeneration)
                            .put("width", sample.width).put("height", sample.height).put("psf", psfJson(sample.stars)) })) })
                })).toString(2).toByteArray(Charsets.UTF_8))
            evidence.finishWrite(output)
        } catch (t: Throwable) { evidence.failWrite(output); throw t }
        persist(_relationships.value.filterNot(key) + value, nextHandEyes)
        _estimate.value = null
    }

    @Synchronized fun invalidateSource(deviceId: String, reason: String) {
        require(reason.isNotBlank())
        (_relationships.value.filter { it.source.deviceId == deviceId }.map { it.source.opticalPathId } +
            _directObservations.value.filter { it.identity.deviceId == deviceId }.map { it.identity.opticalPathId })
            .distinct().forEach(::clearOpticalPathDirection)
        persist(_relationships.value.map { if (it.source.deviceId == deviceId) it.copy(invalidReason = reason) else it },
            _handEyes.value.map { if (it.source.deviceId == deviceId) it.invalidated(reason) else it })
    }

    @Synchronized fun invalidateOpticalPath(opticalPathId: String, reason: String) {
        clearOpticalPathDirection(opticalPathId)
        persist(_relationships.value.map {
            if (it.source.opticalPathId == opticalPathId || it.target.opticalPathId == opticalPathId) it.copy(invalidReason = reason) else it
        }, _handEyes.value.map { if (it.source.opticalPathId == opticalPathId) it.invalidated(reason) else it })
    }

    fun relationshipFor(observation: OpticalObservation, target: OpticalIdentity): OpticalCalibration? =
        _relationships.value.filter { it.appliesTo(observation, target) }.maxByOrNull { it.version }

    fun handEyeFor(observation: OpticalObservation): HandEyeCalibration? =
        _handEyes.value.filter { it.appliesTo(observation) }.maxByOrNull { it.version }

    @Synchronized fun publish(observation: OpticalObservation, target: OpticalIdentity): PointingEstimate? {
        val value = relationshipFor(observation, target)?.estimate(observation, target)
        _estimate.value = value
        return value
    }

    @Synchronized fun publishPropagated(observation: OpticalObservation, pose: SkyPose, target: OpticalIdentity,
                          utcNowMs: Long, elapsedNowMs: Long): PointingEstimate? {
        val relation = relationshipFor(observation, target)
        val ageMs = elapsedNowMs - observation.stamp.elapsedMidpointMs
        val value = if (relation != null && ageMs in 0..60_000) {
            val direction = pose.project(relation.axisInSource).direction()
            val targetPose = relation.targetPoseInSource?.let(pose::apply)
            val pa = targetPose?.let {
                val base = SkyPose.fromSolution(direction, 0.0)
                Math.toDegrees(kotlin.math.atan2(it.up.dot(base.right), it.up.dot(base.up)))
            }
            relation.estimate(observation, target)?.copy(direction = direction, estimateUtcMs = utcNowMs,
                uncertaintyDeg = relation.uncertaintyDeg + observation.uncertaintyDeg +
                    (handEyeFor(observation)?.uncertaintyDeg ?: 0.0) + ageMs * 0.00002,
                propagated = true, imageUpPositionAngleDeg = pa)
        } else null
        _estimate.value = value
        return value
    }

    @Synchronized fun clearCurrentEstimate() { _estimate.value = null }

    private fun psfJson(stars: List<OpticalStarPsf>) = JSONArray(stars.map { star -> JSONObject()
        .put("x", star.x).put("y", star.y).put("sigmaMajorPx", star.sigmaMajorPx).put("sigmaMinorPx", star.sigmaMinorPx)
        .put("snr", star.snr).put("flux", star.flux) })

    private fun persist(values: List<OpticalCalibration>, handEyes: List<HandEyeCalibration> = _handEyes.value) {
        require(values.size <= 100 && handEyes.size <= 100) { "Calibration registry is full" }
        val json = JSONObject().put("schema", 1).put("relationships", JSONArray(values.map(CalibrationJson::encode)))
            .put("handEyes", JSONArray(handEyes.map(HandEyeCalibrationJson::encode)))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1_048_576) { "Calibration registry is full" }
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
            _relationships.value = values
            _handEyes.value = handEyes
        } catch (t: Throwable) { file.failWrite(output); throw t }
    }
    private fun load(): List<OpticalCalibration> = runCatching {
        val data = file.openRead().use { it.readBytes() }
        require(data.size <= 1_048_576)
        val json = JSONObject(data.toString(Charsets.UTF_8))
        require(json.getInt("schema") == 1)
        val values = json.getJSONArray("relationships")
        require(values.length() <= 100)
        (0 until values.length()).map { CalibrationJson.decode(values.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun loadHandEyes(): List<HandEyeCalibration> = runCatching {
        val bytes = file.openRead().use { it.readBytes() }; require(bytes.size <= 1_048_576)
        val values = JSONObject(bytes.toString(Charsets.UTF_8)).optJSONArray("handEyes") ?: return@runCatching emptyList()
        require(values.length() <= 100)
        (0 until values.length()).map { HandEyeCalibrationJson.decode(values.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}

internal object CalibrationJson {
    private fun identity(v: OpticalIdentity) = JSONObject().put("device", v.deviceId).put("lens", v.physicalLensId)
        .put("path", v.opticalPathId).put("installation", v.installationRevision).put("optics", v.opticsRevision)
    private fun identity(v: JSONObject) = OpticalIdentity(v.getString("device"), v.getString("lens"), v.getString("path"), v.getLong("installation"), v.getLong("optics"))
    private fun vector(v: SkyVector) = JSONArray(listOf(v.x, v.y, v.z))
    private fun vector(v: JSONArray) = SkyVector(v.getDouble(0), v.getDouble(1), v.getDouble(2)).also {
        require(kotlin.math.abs(it.dot(it) - 1.0) < 1e-5)
    }
    fun observation(v: OpticalObservation) = JSONObject().put("id", v.id).put("identity", identity(v.identity))
        .put("acquisitionDevice", v.acquisitionDeviceId).put("kind", v.kind.name).put("independentReferenceId", v.independentReferenceId)
        .put("utcMidpointMs", v.stamp.utcMidpointMs).put("elapsedMidpointMs", v.stamp.elapsedMidpointMs)
        .put("exposureDurationMs", v.stamp.exposureDurationMs).put("timeUncertaintyMs", v.stamp.clockUncertaintyMs)
        .put("raDegJ2000", v.direction.raDeg).put("decDegJ2000", v.direction.decDeg).put("uncertaintyDeg", v.uncertaintyDeg)
        .put("scaleDegPerPixel", v.plateScaleDegPerPixel)
        .put("pose", v.pose?.let { JSONArray(listOf(vector(it.right), vector(it.up), vector(it.forward))) })
        .put("geometry", v.geometry?.let { g -> JSONObject().put("width", g.width).put("height", g.height)
            .put("cropLeft", g.cropLeft).put("cropTop", g.cropTop).put("cropWidth", g.cropWidth).put("cropHeight", g.cropHeight)
            .put("parity", g.parity.name).put("axisPixelX", g.axisPixelX).put("axisPixelY", g.axisPixelY).put("intrinsics", g.intrinsicsFingerprint).put("roiSensorScale", g.roiSensorScale) })
    fun encode(v: OpticalCalibration): JSONObject = JSONObject().put("id", v.id).put("version", v.version)
        .put("source", identity(v.source)).put("target", identity(v.target)).put("parity", v.sourceParity.name)
        .put("axis", vector(v.axisInSource)).put("pose", v.targetPoseInSource?.let {
            JSONArray(listOf(vector(it.right), vector(it.up), vector(it.forward)))
        } ?: JSONObject.NULL).put("training", JSONArray(v.trainingObservationIds.toList()))
        .put("validation", JSONArray(v.validationObservationIds.toList())).put("residualDeg", v.residualDeg)
        .put("uncertaintyDeg", v.uncertaintyDeg).put("validatedUtcMs", v.validatedUtcMs)
        .put("targetAcquisitionDeviceId", v.targetAcquisitionDeviceId ?: JSONObject.NULL)
        .put("intrinsics", v.sourceIntrinsicsFingerprint).put("invalidReason", v.invalidReason ?: JSONObject.NULL)
    fun decode(v: JSONObject): OpticalCalibration {
        fun ids(key: String): Set<String> { val a = v.getJSONArray(key); require(a.length() in 1..32); return (0 until a.length()).map { a.getString(it) }.toSet() }
        val pose = v.optJSONArray("pose")?.let { SkyPose(vector(it.getJSONArray(0)), vector(it.getJSONArray(1)), vector(it.getJSONArray(2))) }
        val acquisitionId = if (v.isNull("targetAcquisitionDeviceId")) null else v.getString("targetAcquisitionDeviceId")
        val invalidReason = if (v.isNull("invalidReason")) null else v.getString("invalidReason")
        return OpticalCalibration(v.getString("id"), v.getLong("version"), identity(v.getJSONObject("source")), identity(v.getJSONObject("target")),
            ImageParity.valueOf(v.getString("parity")), vector(v.getJSONArray("axis")), pose, ids("training"), ids("validation"),
            v.getDouble("residualDeg"), v.getDouble("uncertaintyDeg"), v.getLong("validatedUtcMs"), v.getString("intrinsics"),
            invalidReason ?: if (pose != null && acquisitionId.isNullOrBlank()) "missing_reference_camera_identity" else null,
            acquisitionId).also {
            require(it.version > 0 && it.residualDeg.isFinite() && it.residualDeg >= 0 && it.uncertaintyDeg.isFinite() && it.uncertaintyDeg > 0)
        }
    }
}
