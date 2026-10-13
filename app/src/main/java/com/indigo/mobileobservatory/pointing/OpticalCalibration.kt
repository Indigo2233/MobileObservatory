package com.indigo.mobileobservatory.pointing

import kotlin.math.abs

data class CalibrationPair(val camera: OpticalObservation, val reference: OpticalObservation,
                           val pairingUncertaintyDeg: Double = 0.0,
                           val timeAdjustedReference: SkyPose? = null,
                           val measuredPairMotionDeg: Double? = null,
                           val timeAdjustedDirection: SkyDirection? = null,
                           val opticalStability: OpticalPairStabilityEvidence? = null) {
    val referenceDirection get() = timeAdjustedReference?.forward?.direction() ?: timeAdjustedDirection ?: reference.direction
    val referencePose get() = timeAdjustedReference ?: reference.pose
    fun validate() {
        require(camera.kind == OpticalObservationKind.PHONE_PLATE || camera.kind == OpticalObservationKind.AUXILIARY_PLATE)
        require(reference.kind == OpticalObservationKind.MAIN_PLATE || reference.kind == OpticalObservationKind.CENTERED_STAR)
        require(camera.pose != null && camera.geometry?.parity in setOf(ImageParity.NORMAL, ImageParity.MIRRORED))
        fun hasReferencePixel(geometry: CameraGeometry?) = geometry != null &&
            geometry.axisPixelX in -.5..(geometry.width - .5) && geometry.axisPixelY in -.5..(geometry.height - .5)
        require(hasReferencePixel(camera.geometry)) { "Source crop excludes its optical reference pixel" }
        if (reference.kind == OpticalObservationKind.MAIN_PLATE) {
            require(hasReferencePixel(reference.geometry)) { "Main crop excludes its optical reference pixel" }
        }
        require(camera.identity.opticalPathId != reference.identity.opticalPathId)
        require(camera.id != reference.id && camera.independentReferenceId != reference.independentReferenceId)
        require(reference.kind == OpticalObservationKind.CENTERED_STAR || camera.acquisitionDeviceId != reference.acquisitionDeviceId)
            { "One camera cannot be both the source and its independent optical reference" }
        require(camera.stamp.compatibleClock(reference.stamp)) { "Clock changed during sampling" }
        if (camera.kind == OpticalObservationKind.PHONE_PLATE) {
            require(camera.stamp.clockUncertaintyMs <= 250) { "Unknown phone exposure time" }
        } else {
            require(camera.stamp.clockUncertaintyMs <= 15_000 &&
                (timeAdjustedReference != null || timeAdjustedDirection != null)) { "Auxiliary timing needs a complete error budget" }
            requireNotNull(opticalStability) { "Missing independent optical stability evidence" }.validate(camera, reference)
            require(measuredPairMotionDeg != null && measuredPairMotionDeg >= opticalStability.motionUpperBoundDeg)
        }
        if (timeAdjustedReference == null && timeAdjustedDirection == null) {
            require(abs(camera.stamp.elapsedMidpointMs - reference.stamp.elapsedMidpointMs) <= 1_000) { "Reference and phone exposures must overlap" }
            require(reference.stamp.clockUncertaintyMs <= 250) { "Unknown reference exposure time" }
        } else {
            require(reference.kind in setOf(OpticalObservationKind.MAIN_PLATE, OpticalObservationKind.CENTERED_STAR))
            require(timeAdjustedReference == null || reference.pose != null)
            require(measuredPairMotionDeg != null && measuredPairMotionDeg in 0.0..0.05)
            require(reference.stamp.clockUncertaintyMs <= 15_000 && abs(camera.stamp.elapsedMidpointMs - reference.stamp.elapsedMidpointMs) <= 30_000)
            val timeBound = abs(camera.stamp.elapsedMidpointMs - reference.stamp.elapsedMidpointMs) + camera.stamp.clockUncertaintyMs + reference.stamp.clockUncertaintyMs
            require(pairingUncertaintyDeg >= timeBound * 0.0042 / 1000 + measuredPairMotionDeg) { "Pairing error budget excludes temporal uncertainty" }
        }
        require(pairingUncertaintyDeg.isFinite() && pairingUncertaintyDeg >= 0)
    }
    val uncertaintyDeg get() = camera.uncertaintyDeg + reference.uncertaintyDeg + pairingUncertaintyDeg
}

data class OpticalCalibration(val id: String, val version: Long, val source: OpticalIdentity, val target: OpticalIdentity,
    val sourceParity: ImageParity, val axisInSource: SkyVector, val targetPoseInSource: SkyPose?,
    val trainingObservationIds: Set<String>, val validationObservationIds: Set<String>,
    val residualDeg: Double, val uncertaintyDeg: Double, val validatedUtcMs: Long,
    val sourceIntrinsicsFingerprint: String, val invalidReason: String? = null,
    val targetAcquisitionDeviceId: String? = null) {
    val model get() = if (targetPoseInSource == null) RelationshipModel.AXIS_ONLY else RelationshipModel.FULL_POSE
    val valid get() = invalidReason == null && validationObservationIds.isNotEmpty()
    fun appliesTo(observation: OpticalObservation, targetIdentity: OpticalIdentity): Boolean = valid &&
        source == observation.identity && target == targetIdentity && observation.geometry?.parity == sourceParity &&
        sourceIntrinsicsFingerprint == observation.geometry.intrinsicsFingerprint && observation.pose != null
    fun estimate(observation: OpticalObservation, targetIdentity: OpticalIdentity): PointingEstimate? {
        if (!appliesTo(observation, targetIdentity)) return null
        val pose = observation.pose ?: return null
        val direction = pose.project(axisInSource).direction()
        val angle = targetPoseInSource?.let { local ->
            val targetPose = pose.apply(local)
            val base = SkyPose.fromSolution(direction, 0.0)
            Math.toDegrees(kotlin.math.atan2(targetPose.up.dot(base.right), targetPose.up.dot(base.up)))
        }
        return PointingEstimate(direction, target.opticalPathId, observation.stamp.utcMidpointMs,
            observation.stamp.utcMidpointMs, uncertaintyDeg + observation.uncertaintyDeg, id, version,
            observation.kind, setOf(observation.id) + trainingObservationIds + validationObservationIds, false, angle)
    }
}

/** Training and validation are separate: a validation point is never used to fit its own residual. */
object OpticalCalibrationFitter {
    fun fit(training: CalibrationPair, validation: CalibrationPair, previousVersion: Long = 0,
            maxResidualDeg: Double = 0.5, minimumSeparationDeg: Double = 5.0): OpticalCalibration {
        training.validate(); validation.validate()
        require(training.camera.identity == validation.camera.identity && training.reference.identity == validation.reference.identity)
        require(training.reference.acquisitionDeviceId == validation.reference.acquisitionDeviceId) { "Reference camera changed while calibrating" }
        require(listOf(training.reference, validation.reference).none { it.kind == OpticalObservationKind.CENTERED_STAR && it.pose != null })
            { "A centered star constrains an axis, not camera roll" }
        require(training.camera.geometry == validation.camera.geometry) { "Camera geometry changed while calibrating" }
        require(setOf(training.camera.id, training.reference.id).intersect(setOf(validation.camera.id, validation.reference.id)).isEmpty())
        require(setOf(training.camera.independentReferenceId, training.reference.independentReferenceId)
            .intersect(setOf(validation.camera.independentReferenceId, validation.reference.independentReferenceId)).isEmpty()) { "Validation must be independent" }
        require(training.referenceDirection.vector().angleDeg(validation.referenceDirection.vector()) >= minimumSeparationDeg) { "Choose another direction to validate" }
        val first = requireNotNull(training.camera.pose)
        val axis = first.unproject(training.referenceDirection.vector())
        val residual = requireNotNull(validation.camera.pose).project(axis).angleDeg(validation.referenceDirection.vector())
        require(residual <= maxResidualDeg && maxOf(training.uncertaintyDeg, validation.uncertaintyDeg) <= maxResidualDeg) { "Validation error exceeds the selected tolerance" }
        val relativePose = training.referencePose?.let(first::relative)
        if (relativePose != null) {
            val predicted = requireNotNull(validation.camera.pose).apply(relativePose)
            val measured = requireNotNull(validation.referencePose) { "Full pose needs image rotation at both samples" }
            require(predicted.up.angleDeg(measured.up) <= maxResidualDeg) { "Image roll validation failed" }
        }
        return OpticalCalibration("${training.camera.identity.opticalPathId}->${training.reference.identity.opticalPathId}",
            previousVersion + 1, training.camera.identity, training.reference.identity,
            requireNotNull(training.camera.geometry).parity, axis, relativePose,
            setOf(training.camera.id, training.reference.id), setOf(validation.camera.id, validation.reference.id),
            residual, maxOf(training.uncertaintyDeg, validation.uncertaintyDeg) + residual,
            validation.camera.stamp.utcMidpointMs, training.camera.geometry.intrinsicsFingerprint,
            targetAcquisitionDeviceId = training.reference.acquisitionDeviceId.takeIf { training.reference.kind == OpticalObservationKind.MAIN_PLATE })
    }
}
