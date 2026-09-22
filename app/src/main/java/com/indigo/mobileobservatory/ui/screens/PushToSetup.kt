package com.indigo.mobileobservatory.ui.screens

import com.indigo.mobileobservatory.camera.PhoneCameraCapability
import com.indigo.mobileobservatory.camera.PhoneManualExposure

internal data class PushToCaptureSettings(
    val cameraId: String,
    val exposureSeconds: Double,
    val iso: Int,
    val preferRaw: Boolean,
    val autoIso: Boolean = true,
    val burstFrameCount: Int = 1
)

internal fun defaultPushToSettings(camera: PhoneCameraCapability): PushToCaptureSettings {
    val range = camera.captureExposureRange()
    return defaultPushToSettings(
        cameraId = camera.cameraId,
        minimumExposureSeconds = range.start.toDouble(),
        maximumExposureSeconds = range.endInclusive.toDouble(),
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
    val exposure = 1.0.coerceIn(minimumExposureSeconds, maximumExposureSeconds)
    val iso = 1600.coerceIn(minimumIso, maximumIso)
    return PushToCaptureSettings(
        cameraId = cameraId,
        exposureSeconds = exposure,
        iso = iso,
        preferRaw = supportsRaw,
        autoIso = true,
        burstFrameCount = PhoneManualExposure.defaultBurstFrames(maximumExposureSeconds.toFloat())
    )
}
