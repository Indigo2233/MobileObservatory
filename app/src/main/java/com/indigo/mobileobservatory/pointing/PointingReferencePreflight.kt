package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.Camera
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi

/** Snapshot of predictable capture conditions; no motion, SDK writes or image allocation. */
data class PointingReferenceCameraState(
    val open: Boolean,
    val recordsScience: Boolean,
    val rawLayoutKnown: Boolean,
    val acquisitionGeneration: Long?,
    val requiresHighBitStill: Boolean,
    val capturing: Boolean,
    val format: PixelFormat,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val roi: Roi,
    val roiSensorScale: Int,
    val exposureUs: Float,
    val hardwareExposureMaxUs: Float,
    val softwareStacking: Boolean
) {
    companion object {
        fun of(camera: Camera) = PointingReferenceCameraState(camera.isOpen.value,
            camera.recordsLiveViewAsScience, camera.sciencePixelTraits != null,
            camera.scienceCaptureGeneration, camera.requiresHighBitScienceCapture,
            camera.isCapturing.value, camera.currentPixelFormat,
            camera.cameraInfo?.sensorWidth ?: 0, camera.cameraInfo?.sensorHeight ?: 0,
            camera.currentRoi, camera.roiSensorScale, camera.currentExposureUs,
            camera.hwExposureMaxUs, camera.supportsSoftwareStacking)
    }
}

data class PointingReferenceState(
    val camera: PointingReferenceCameraState?,
    val databaseInstalled: Boolean,
    val recordingBusy: Boolean = false,
    val liveStackBusy: Boolean = false,
    val calibrationBusy: Boolean = false,
    val referenceBusy: Boolean = false,
    val snapshotBusy: Boolean = false,
    val settingsBusy: Boolean = false,
    val autoExposureOff: Boolean = true,
    val previewPaused: Boolean = false,
    val filterMoving: Boolean = false,
    val rotatorMoving: Boolean = false
)

enum class PointingReferenceIssue {
    CAMERA, DATABASE, BUSY, SETTINGS, SCIENCE_MODE, AUTO_EXPOSURE, PREVIEW, FILTER, ROTATOR,
    OPTICAL_AXIS, EXPOSURE
}

/** Used immediately before every automatic slew and again when acquiring the actual reference. */
object PointingReferencePreflight {
    const val FRAME_TIMEOUT_MS = 30_000L

    fun issue(state: PointingReferenceState): PointingReferenceIssue? {
        val camera = state.camera ?: return PointingReferenceIssue.CAMERA
        if (!camera.open) return PointingReferenceIssue.CAMERA
        if (!state.databaseInstalled) return PointingReferenceIssue.DATABASE
        if (state.recordingBusy || state.liveStackBusy || state.calibrationBusy ||
            state.referenceBusy || state.snapshotBusy) return PointingReferenceIssue.BUSY
        if (state.settingsBusy) return PointingReferenceIssue.SETTINGS
        if (!camera.recordsScience || !camera.rawLayoutKnown || camera.acquisitionGeneration == null ||
            camera.requiresHighBitStill || camera.format.isRgb) return PointingReferenceIssue.SCIENCE_MODE
        if (!state.autoExposureOff) return PointingReferenceIssue.AUTO_EXPOSURE
        if (state.filterMoving) return PointingReferenceIssue.FILTER
        if (state.rotatorMoving) return PointingReferenceIssue.ROTATOR
        if (state.previewPaused || !camera.capturing) return PointingReferenceIssue.PREVIEW
        if (!camera.exposureUs.isFinite() || camera.exposureUs <= 0 ||
            camera.exposureUs > FRAME_TIMEOUT_MS * 1000f ||
            camera.softwareStacking && (!camera.hardwareExposureMaxUs.isFinite() ||
                camera.exposureUs > camera.hardwareExposureMaxUs)) return PointingReferenceIssue.EXPOSURE
        val axisAvailable = runCatching {
            ReferencePixelMapper.sensorCenter(camera.sensorWidth, camera.sensorHeight, camera.roi,
                camera.roiSensorScale, camera.roi.width, camera.roi.height)
        }.isSuccess
        if (!axisAvailable) return PointingReferenceIssue.OPTICAL_AXIS
        return null
    }
}
