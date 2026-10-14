package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointingReferencePreflightTest {
    private val camera = PointingReferenceCameraState(true, true, true, 7, false, true,
        PixelFormat.MONO16, 1024, 768, Roi(0, 0, 1024, 768), 1, 1_000_000f, 60_000_000f, false)
    private val ready = PointingReferenceState(camera, true)

    @Test fun predictableUnavailableConditionsAreRejectedBeforeAReferenceCanBeUsed() {
        val cases = listOf(
            ready.copy(camera = null) to PointingReferenceIssue.CAMERA,
            ready.copy(camera = camera.copy(open = false)) to PointingReferenceIssue.CAMERA,
            ready.copy(databaseInstalled = false) to PointingReferenceIssue.DATABASE,
            ready.copy(recordingBusy = true) to PointingReferenceIssue.BUSY,
            ready.copy(liveStackBusy = true) to PointingReferenceIssue.BUSY,
            ready.copy(calibrationBusy = true) to PointingReferenceIssue.BUSY,
            ready.copy(referenceBusy = true) to PointingReferenceIssue.BUSY,
            ready.copy(snapshotBusy = true) to PointingReferenceIssue.BUSY,
            ready.copy(settingsBusy = true) to PointingReferenceIssue.SETTINGS,
            ready.copy(camera = camera.copy(recordsScience = false)) to PointingReferenceIssue.SCIENCE_MODE,
            ready.copy(camera = camera.copy(rawLayoutKnown = false)) to PointingReferenceIssue.SCIENCE_MODE,
            ready.copy(camera = camera.copy(acquisitionGeneration = null)) to PointingReferenceIssue.SCIENCE_MODE,
            ready.copy(camera = camera.copy(requiresHighBitStill = true)) to PointingReferenceIssue.SCIENCE_MODE,
            ready.copy(camera = camera.copy(format = PixelFormat.RGB24)) to PointingReferenceIssue.SCIENCE_MODE,
            ready.copy(camera = camera.copy(format = PixelFormat.RGB48)) to PointingReferenceIssue.SCIENCE_MODE,
            ready.copy(autoExposureOff = false) to PointingReferenceIssue.AUTO_EXPOSURE,
            ready.copy(filterMoving = true) to PointingReferenceIssue.FILTER,
            ready.copy(rotatorMoving = true) to PointingReferenceIssue.ROTATOR,
            ready.copy(previewPaused = true) to PointingReferenceIssue.PREVIEW,
            ready.copy(camera = camera.copy(capturing = false)) to PointingReferenceIssue.PREVIEW
        )
        cases.forEach { (state, expected) -> assertEquals(state.toString(), expected, PointingReferencePreflight.issue(state)) }
        assertNull(PointingReferencePreflight.issue(ready))
    }

    @Test fun sourceCropMustIncludeThePhysicalAxisInItsActualVendorCoordinateDomain() {
        assertEquals(PointingReferenceIssue.OPTICAL_AXIS,
            PointingReferencePreflight.issue(ready.copy(camera = camera.copy(roi = Roi(0, 0, 128, 128)))))
        assertEquals(PointingReferenceIssue.OPTICAL_AXIS,
            PointingReferencePreflight.issue(ready.copy(camera = camera.copy(sensorWidth = 0))))
        assertNull(PointingReferencePreflight.issue(ready.copy(camera = camera.copy(roi = Roi(400, 300, 200, 150)))))
        assertNull(PointingReferencePreflight.issue(ready.copy(camera = camera.copy(roi = Roi(0, 0, 512, 384), roiSensorScale = 2))))
        assertEquals(PointingReferenceIssue.OPTICAL_AXIS,
            PointingReferencePreflight.issue(ready.copy(camera = camera.copy(roi = Roi(0, 0, 300, 250), roiSensorScale = 1))))
    }

    @Test fun onlyACompleteSingleExposureThatFitsTheCaptureDeadlineIsAccepted() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 31_000_000f).forEach { exposure ->
            assertEquals(PointingReferenceIssue.EXPOSURE,
                PointingReferencePreflight.issue(ready.copy(camera = camera.copy(exposureUs = exposure))))
        }
        assertEquals(PointingReferenceIssue.EXPOSURE, PointingReferencePreflight.issue(ready.copy(
            camera = camera.copy(softwareStacking = true, hardwareExposureMaxUs = 500_000f))))
        assertNull(PointingReferencePreflight.issue(ready.copy(camera = camera.copy(exposureUs = 25_000_000f))))
        assertNull(PointingReferencePreflight.issue(ready.copy(camera = camera.copy(
            softwareStacking = true, hardwareExposureMaxUs = 2_000_000f))))
    }

}
