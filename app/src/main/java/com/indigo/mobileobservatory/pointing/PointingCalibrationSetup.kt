package com.indigo.mobileobservatory.pointing

enum class PointingCameraSource { PHONE, AUXILIARY }

/** Adjustable is rigid only after locking; a handheld sensor cannot describe the telescope. */
enum class PointingInstallation { FIXED, ADJUSTABLE, UNFIXED }

data class PointingCalibrationSetup(
    val source: PointingCameraSource,
    val installation: PointingInstallation,
    val locked: Boolean
) {
    val canCalibrate get() = installation != PointingInstallation.UNFIXED && locked
    val canAdjust get() = installation == PointingInstallation.ADJUSTABLE
}
