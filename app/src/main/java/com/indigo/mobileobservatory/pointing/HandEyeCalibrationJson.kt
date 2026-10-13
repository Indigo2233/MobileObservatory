package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.ObserverSite
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Saved extrinsics retain the original four (or more) exposures and synchronous IMU evidence. */
object HandEyeCalibrationJson {
    fun encode(value: HandEyeCalibration): JSONObject = JSONObject().put("schema", 1)
        .put("id", value.id).put("version", value.version).put("opticalInImu", pose(value.opticalInImu))
        .put("training", JSONArray(value.trainingSamples.map(::sample)))
        .put("validation", sample(value.validationSample)).put("fitResidualDeg", value.fitResidualDeg)
        .put("fitMaximumResidualDeg", value.fitMaximumResidualDeg)
        .put("validationResidualDeg", value.validationResidualDeg).put("uncertaintyDeg", value.uncertaintyDeg)
        .put("maxResidualDeg", value.maxResidualDeg).put("minimumRotationDeg", value.minimumRotationDeg)
        .put("minimumAxisSeparationDeg", value.minimumAxisSeparationDeg)
        .put("invalidReason", value.invalidReason ?: JSONObject.NULL)

    /** Refit and independently verify on load; a saved matrix or claimed residual alone is insufficient. */
    fun decode(value: JSONObject): HandEyeCalibration {
        require(value.getInt("schema") == 1)
        val training = value.getJSONArray("training")
        require(training.length() in 3..32)
        val version = value.getLong("version"); require(version > 0)
        val reconstructed = HandEyeCalibrationFitter.fit(
            (0 until training.length()).map { sample(training.getJSONObject(it)) },
            sample(value.getJSONObject("validation")), version - 1,
            value.getDouble("maxResidualDeg"), value.getDouble("minimumRotationDeg"),
            value.getDouble("minimumAxisSeparationDeg"))
        require(value.getString("id") == reconstructed.id &&
            rotationDistanceDeg(pose(value.getJSONArray("opticalInImu")), reconstructed.opticalInImu) < 1e-6) {
            "Saved installation rotation does not match its calibration evidence"
        }
        listOf("fitResidualDeg" to reconstructed.fitResidualDeg,
            "fitMaximumResidualDeg" to reconstructed.fitMaximumResidualDeg,
            "validationResidualDeg" to reconstructed.validationResidualDeg,
            "uncertaintyDeg" to reconstructed.uncertaintyDeg).forEach { (key, expected) ->
            val stored = value.getDouble(key)
            require(stored.isFinite() && abs(stored - expected) < 1e-7) { "Saved $key does not match its evidence" }
        }
        return if (value.isNull("invalidReason")) reconstructed else reconstructed.invalidated(value.getString("invalidReason"))
    }

    private fun sample(v: HandEyeSample) = JSONObject().put("observation", observation(v.observation))
        .put("sensorPose", pose(v.sensorPose)).put("sensorUtcMs", v.sensorUtcMs).put("sensorElapsedMs", v.sensorElapsedMs)
        .put("sensorSessionId", v.sensorSessionId)
        .put("site", JSONObject().put("latitudeDeg", v.site.latitudeDeg).put("longitudeDeg", v.site.longitudeDeg)
            .put("elevationMeters", v.site.elevationMeters))

    private fun sample(v: JSONObject): HandEyeSample {
        val site = v.getJSONObject("site")
        return HandEyeSample(observation(v.getJSONObject("observation")), pose(v.getJSONArray("sensorPose")),
            v.getLong("sensorUtcMs"), v.getLong("sensorElapsedMs"),
            ObserverSite(site.getDouble("latitudeDeg"), site.getDouble("longitudeDeg"), site.getDouble("elevationMeters")),
            v.getString("sensorSessionId"))
    }

    private fun vector(v: SkyVector) = JSONArray(listOf(v.x, v.y, v.z))
    private fun vector(v: JSONArray): SkyVector {
        require(v.length() == 3)
        return SkyVector(v.getDouble(0), v.getDouble(1), v.getDouble(2))
    }
    private fun pose(v: SkyPose) = JSONArray(listOf(vector(v.right), vector(v.up), vector(v.forward)))
    private fun pose(v: JSONArray): SkyPose {
        require(v.length() == 3)
        return SkyPose(vector(v.getJSONArray(0)), vector(v.getJSONArray(1)), vector(v.getJSONArray(2)))
    }
    private fun identity(v: OpticalIdentity) = JSONObject().put("device", v.deviceId).put("lens", v.physicalLensId)
        .put("path", v.opticalPathId).put("installation", v.installationRevision).put("optics", v.opticsRevision)
    private fun identity(v: JSONObject) = OpticalIdentity(v.getString("device"), v.getString("lens"),
        v.getString("path"), v.getLong("installation"), v.getLong("optics"))

    private fun observation(v: OpticalObservation) = JSONObject().put("id", v.id).put("identity", identity(v.identity))
        .put("acquisitionDevice", v.acquisitionDeviceId).put("kind", v.kind.name).put("independentReferenceId", v.independentReferenceId)
        .put("utcMidpointMs", v.stamp.utcMidpointMs).put("elapsedMidpointMs", v.stamp.elapsedMidpointMs)
        .put("exposureDurationMs", v.stamp.exposureDurationMs).put("timeUncertaintyMs", v.stamp.clockUncertaintyMs)
        .put("raDegJ2000", v.direction.raDeg).put("decDegJ2000", v.direction.decDeg).put("uncertaintyDeg", v.uncertaintyDeg)
        .put("scaleDegPerPixel", v.plateScaleDegPerPixel)
        .put("pose", v.pose?.let(::pose) ?: JSONObject.NULL)
        .put("geometry", v.geometry?.let { g -> JSONObject().put("width", g.width).put("height", g.height)
            .put("cropLeft", g.cropLeft).put("cropTop", g.cropTop).put("cropWidth", g.cropWidth).put("cropHeight", g.cropHeight)
            .put("parity", g.parity.name).put("axisPixelX", g.axisPixelX).put("axisPixelY", g.axisPixelY)
            .put("intrinsics", g.intrinsicsFingerprint).put("roiSensorScale", g.roiSensorScale) } ?: JSONObject.NULL)

    private fun observation(v: JSONObject): OpticalObservation {
        val geometry = v.optJSONObject("geometry")?.let { g -> CameraGeometry(g.getInt("width"), g.getInt("height"),
            g.getInt("cropLeft"), g.getInt("cropTop"), g.getInt("cropWidth"), g.getInt("cropHeight"),
            ImageParity.valueOf(g.getString("parity")), g.getDouble("axisPixelX"), g.getDouble("axisPixelY"),
            g.getString("intrinsics"), g.optInt("roiSensorScale", 1)) }
        return OpticalObservation(v.getString("id"), identity(v.getJSONObject("identity")),
            ObservationStamp(v.getLong("utcMidpointMs"), v.getLong("elapsedMidpointMs"),
                v.getLong("exposureDurationMs"), v.getLong("timeUncertaintyMs")),
            SkyDirection(v.getDouble("raDegJ2000"), v.getDouble("decDegJ2000")),
            v.optJSONArray("pose")?.let(::pose), geometry, v.getDouble("uncertaintyDeg"),
            OpticalObservationKind.valueOf(v.getString("kind")), v.getString("independentReferenceId"),
            v.getString("acquisitionDevice"), if (v.isNull("scaleDegPerPixel")) null else v.getDouble("scaleDegPerPixel"))
    }
}
