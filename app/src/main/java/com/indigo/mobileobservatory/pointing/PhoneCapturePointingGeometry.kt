package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.PhoneCaptureMetadata

/** Actual physical identity and pixel geometry are shared by prior selection, stacking and evidence. */
internal fun PhoneCaptureMetadata.pointingIdentity(installationRevision: Long, opticsRevision: Long) =
    OpticalIdentity("phone", "$logicalCameraId/${physicalCameraId ?: logicalCameraId}",
        "phone", installationRevision, opticsRevision)

internal fun PhoneCaptureMetadata.pointingGeometry(width: Int, height: Int, usedRaw: Boolean, mirrored: Boolean) =
    CameraGeometry(width, height, cropLeftPx ?: 0, cropTopPx ?: 0, cropWidthPx ?: width, cropHeightPx ?: height,
        if (mirrored) ImageParity.MIRRORED else ImageParity.NORMAL,
        intrinsicsFingerprint = listOf(focalLengthMm, sensorWidthMm, sensorHeightMm,
            activeArrayLeftPx, activeArrayTopPx, activeArrayWidthPx, activeArrayHeightPx,
            preCorrectionActiveArrayLeftPx, preCorrectionActiveArrayTopPx,
            preCorrectionActiveArrayWidthPx, preCorrectionActiveArrayHeightPx,
            cropLeftPx, cropTopPx, cropWidthPx, cropHeightPx, sensorOrientation, calibrationCoordinateDomain,
            distortionCorrectionMode, lensCalibration, usedRaw, solveFootprint, solveReduction).joinToString("/"))

/** X is the physical installation rotation; a parity flag alone cannot supply it. */
internal fun AttitudeSnapshot.opticalPoseForCapture(mirrored: Boolean, opticalInImu: SkyPose?): SkyPose? {
    if (mirrored && opticalInImu == null) return null
    val sensor = imuPose() ?: return null
    return opticalInImu?.let(sensor::apply) ?: sensor
}
