package com.indigo.mobileobservatory.pointing

/** Predictable setup failures, shared by the acquisition gate and its visible explanation. */
enum class CalibrationBlocker {
    UNFIXED, UNLOCKED, SITE, PHONE_PERMISSION, PHONE_LENS, MAIN_OPTICS, AUXILIARY_OPTICS,
    AUXILIARY_CAMERA, AUXILIARY_FORMAT, GUIDING, AUXILIARY_SETTINGS, AUXILIARY_EXPOSURE,
    MAIN_CAMERA, DATABASE, CENTERING
}

/** No hardware writes or platform dependencies: only conditions of the selected optical paths. */
data class PointingCalibrationReadiness(
    val source: PointingCameraSource,
    val mainCamera: Boolean,
    val mainOpticsReady: Boolean,
    val auxiliaryOpticsReady: Boolean,
    val siteReady: Boolean,
    val cameraPermission: Boolean,
    val phoneLensReady: Boolean,
    val auxiliaryConnected: Boolean,
    val auxiliaryCaptureReady: Boolean,
    val guiding: Boolean,
    val auxiliarySettingsBusy: Boolean,
    val auxiliaryExposureReady: Boolean,
    val mainConnected: Boolean,
    val databaseReady: Boolean,
    val centeringReady: Boolean,
    val installation: PointingInstallation,
    val locked: Boolean
) {

    /** Rough alignment can acquire before locking; a saved calibration must also be rigid. */
    val acquisitionIssues: List<CalibrationBlocker> = buildList {
        if (!siteReady) add(CalibrationBlocker.SITE)

        // Sensor geometry cannot be verified until the primary reference camera is connected.
        if (mainCamera && !mainConnected) add(CalibrationBlocker.MAIN_CAMERA)
        else if (!mainOpticsReady) add(CalibrationBlocker.MAIN_OPTICS)

        if (source == PointingCameraSource.PHONE) {
            if (!cameraPermission) add(CalibrationBlocker.PHONE_PERMISSION)
            if (!phoneLensReady) add(CalibrationBlocker.PHONE_LENS)
        } else {
            if (!auxiliaryOpticsReady) add(CalibrationBlocker.AUXILIARY_OPTICS)
            if (!auxiliaryConnected) add(CalibrationBlocker.AUXILIARY_CAMERA)
            else {
                // Report format, exposure and pending writes only while the device is connected.
                if (!auxiliaryCaptureReady) add(CalibrationBlocker.AUXILIARY_FORMAT)
                if (auxiliarySettingsBusy) add(CalibrationBlocker.AUXILIARY_SETTINGS)
                if (!auxiliaryExposureReady) add(CalibrationBlocker.AUXILIARY_EXPOSURE)
            }
            if (guiding) add(CalibrationBlocker.GUIDING)
        }

        // Phone wide-field solving uses its own catalog; external camera solving requires ASTAP.
        if ((mainCamera || source == PointingCameraSource.AUXILIARY) && !databaseReady)
            add(CalibrationBlocker.DATABASE)
        if (!mainCamera && !centeringReady) add(CalibrationBlocker.CENTERING)
    }

    val issues: List<CalibrationBlocker> = buildList {
        if (installation == PointingInstallation.UNFIXED) add(CalibrationBlocker.UNFIXED)
        else if (!locked) add(CalibrationBlocker.UNLOCKED)
        addAll(acquisitionIssues)
    }

    val canStart: Boolean get() = issues.isEmpty()
    val canAcquire: Boolean get() = acquisitionIssues.isEmpty()
}
