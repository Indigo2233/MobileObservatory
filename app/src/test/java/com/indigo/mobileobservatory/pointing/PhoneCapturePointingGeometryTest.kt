package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.PhoneCaptureMetadata
import org.junit.Assert.*
import org.junit.Test

class PhoneCapturePointingGeometryTest {
    private val calibration = CameraLensCalibration(1200.0, 1201.0, 600.0, 400.0, .01,
        -.12, .03, -.01, .001, -.002)
    private val metadata = PhoneCaptureMetadata("0", "2", 4.4, 5.8, 4.2,
        10, 12, 1200, 800, 6, 8, 1210, 810,
        10, 12, 1200, 800, 90, 0,
        LensCalibrationCoordinateDomain.PRE_CORRECTION, calibration, null, null)

    @Test fun calibrationFingerprintIncludesEveryIntrinsicAndDistortionCoefficient() {
        val original = metadata.pointingGeometry(1200, 800, true, true)
        val changes = listOf(calibration.copy(focalX = 1199.0), calibration.copy(focalY = 1199.0),
            calibration.copy(principalX = 601.0), calibration.copy(principalY = 401.0),
            calibration.copy(skew = .02), calibration.copy(radialK1 = -.1),
            calibration.copy(radialK2 = .02), calibration.copy(radialK3 = -.02),
            calibration.copy(tangentialP1 = .002), calibration.copy(tangentialP2 = -.003))
        changes.forEach { changed -> assertNotEquals(original.intrinsicsFingerprint,
            metadata.copy(lensCalibration = changed).pointingGeometry(1200, 800, true, true).intrinsicsFingerprint) }
        assertNotEquals(original.intrinsicsFingerprint,
            metadata.copy(lensCalibration = null).pointingGeometry(1200, 800, true, true).intrinsicsFingerprint)
    }

    @Test fun captureIdentityAndGeometryDetectPhysicalLensCropPixelDomainAndReadoutChanges() {
        val identity = metadata.pointingIdentity(4, 3)
        val geometry = metadata.pointingGeometry(1200, 800, true, true)
        assertNotEquals(identity, metadata.copy(physicalCameraId = "3").pointingIdentity(4, 3))
        assertNotEquals(identity, metadata.pointingIdentity(5, 3))
        assertNotEquals(identity, metadata.pointingIdentity(4, 4))
        assertNotEquals(geometry, metadata.copy(cropLeftPx = 11).pointingGeometry(1200, 800, true, true))
        assertNotEquals(geometry, metadata.copy(sensorOrientation = 270).pointingGeometry(1200, 800, true, true))
        assertNotEquals(geometry, metadata.copy(calibrationCoordinateDomain = LensCalibrationCoordinateDomain.POST_CORRECTION)
            .pointingGeometry(1200, 800, true, true))
        assertNotEquals(geometry, metadata.pointingGeometry(1200, 800, false, true))
        assertNotEquals(geometry, metadata.pointingGeometry(1200, 800, true, false))
        assertNotEquals(geometry, metadata.pointingGeometry(1190, 800, true, true))
    }

    @Test fun virtualOpticalAxesApplyTheMeasuredExtrinsicAndUnknownMirrorHasNoImuPrior() {
        val sensor = AttitudeSnapshot(1_000, Direction3(0.0, 0.0, 1.0), Direction3(0.0, 1.0, 0.0), 500)
        val x = SkyPose(SkyVector(0.0, 0.0, -1.0), SkyVector(0.0, 1.0, 0.0), SkyVector(1.0, 0.0, 0.0))
        val virtual = requireNotNull(sensor.opticalPoseForCapture(true, x))
        assertTrue(virtual.forward.angleDeg(SkyVector(1.0, 0.0, 0.0)) < 1e-8)
        assertTrue(virtual.up.angleDeg(SkyVector(0.0, 1.0, 0.0)) < 1e-8)
        assertNull(sensor.opticalPoseForCapture(true, null))
        assertNull(sensor.copy(imageUp = null).opticalPoseForCapture(true, x))
        assertEquals(sensor.imuPose(), sensor.opticalPoseForCapture(false, null))
    }
}
