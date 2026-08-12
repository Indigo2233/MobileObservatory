package com.indigo.mobileobservatory.ui.screens

import com.indigo.mobileobservatory.camera.PhoneCameraCapability

internal data class PushToCaptureSettings(
    val cameraId: String,
    val exposureSeconds: Double,
    val iso: Int,
    val preferRaw: Boolean
)

internal fun defaultPushToSettings(camera: PhoneCameraCapability): PushToCaptureSettings {
    return defaultPushToSettings(
        cameraId = camera.cameraId,
        minimumExposureSeconds = camera.minExposureSeconds ?: 0.01,
        maximumExposureSeconds = camera.maxExposureSeconds ?: 2.0,
        minimumIso = camera.isoRange?.lower ?: 100,
        maximumIso = camera.isoRange?.upper ?: 1600,
        supportsRaw = camera.supportsRaw
    )
}

internal fun defaultPushToSettings(
    cameraId: String,
    minimumExposureSeconds: Double,
    maximumExposureSeconds: Double,
    minimumIso: Int,
    maximumIso: Int,
    supportsRaw: Boolean
): PushToCaptureSettings {
    val exposure = 2.0.coerceIn(minimumExposureSeconds, maximumExposureSeconds)
    val iso = 1600.coerceIn(minimumIso, maximumIso)
    return PushToCaptureSettings(
        cameraId = cameraId,
        exposureSeconds = exposure,
        iso = iso,
        preferRaw = supportsRaw
    )
}
