package com.indigo.mobileobservatory.pointing

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PointingRepositoryInstrumentedTest {
    private fun context(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "pointing-test-${System.nanoTime()}").also { it.mkdirs() }
        return object : ContextWrapper(base) { override fun getFilesDir() = directory }
    }
    private fun pair(id: String, ra: Double): CalibrationPair {
        val pose = SkyPose.fromSolution(SkyDirection(ra, 20.0), 0.0)
        val stamp = ObservationStamp(1_700_000_000_000, 9000, 1000, 2)
        val phone = OpticalObservation("phone$id", OpticalIdentity("phone", "0/2", "phone"), stamp,
            SkyDirection(ra, 20.0), pose, CameraGeometry(1000, 800, parity = ImageParity.NORMAL, intrinsicsFingerprint = "f1"), .01, OpticalObservationKind.PHONE_PLATE)
        val reference = OpticalObservation("ref$id", OpticalIdentity("telescope", "axis", "primary"), stamp,
            SkyDirection(ra, 20.0), null, null, .02, OpticalObservationKind.CENTERED_STAR)
        return CalibrationPair(phone, reference)
    }
    @Test fun validatedRelationshipAndRawEvidencePersistButArrivalDoesNot() {
        val context = context(); val pairs = listOf(pair("1", 10.0), pair("2", 70.0))
        val calibration = OpticalCalibrationFitter.fit(pairs[0], pairs[1])
        val store = PointingRepository(context)
        store.saveValidated(calibration, pairs)
        assertNotNull(store.publish(pairs[1].camera, pairs[1].reference.identity))
        val reopened = PointingRepository(context)
        assertEquals(calibration, reopened.relationships.value.single())
        assertNull(reopened.estimate.value)
        assertEquals(1, File(context.filesDir, "pointing").listFiles()!!.count { it.name.startsWith("evidence-") })
    }
    @Test fun failedReplacementAndLocalInvalidationPreserveOtherRelationships() {
        val context = context(); val pairs = listOf(pair("1", 10.0), pair("2", 70.0))
        val calibration = OpticalCalibrationFitter.fit(pairs[0], pairs[1])
        val store = PointingRepository(context)
        store.saveValidated(calibration, pairs)
        assertThrows(IllegalArgumentException::class.java) { store.saveValidated(calibration.copy(version = 2), listOf(pairs[1], pairs[1])) }
        assertEquals(calibration, PointingRepository(context).relationships.value.single())
        store.invalidateOpticalPath("secondary", "changed finder")
        assertTrue(store.relationships.value.single().valid)
        store.invalidateSource("phone", "bracket moved")
        assertFalse(PointingRepository(context).relationships.value.single().valid)
        assertNull(store.publish(pairs[1].camera, pairs[1].reference.identity))
    }

    @Test fun opticalPathChangeRemovesOnlyItsDirectionsAndRejectsLateSolve() {
        val store = PointingRepository(context())
        fun observation(path: String) = pair(path, 10.0).camera.copy(
            identity = OpticalIdentity(path, "sensor", path), acquisitionDeviceId = "camera-$path",
            kind = if (path == "primary") OpticalObservationKind.MAIN_PLATE else OpticalObservationKind.AUXILIARY_PLATE)
        val primary = observation("primary"); val secondary = observation("secondary")
        val oldPrimary = store.captureRevision("primary"); val oldSecondary = store.captureRevision("secondary")
        store.recordDirectObservation(primary, oldPrimary); store.recordDirectObservation(secondary, oldSecondary)
        store.invalidateOpticalPath("primary", "telescope_changed")
        assertEquals(listOf(secondary), store.directObservations.value)
        assertThrows(IllegalStateException::class.java) { store.recordDirectObservation(primary, oldPrimary) }
        store.recordDirectObservation(secondary, oldSecondary)
        store.observeConnectedCamera("secondary", "replacement-camera")
        assertTrue(store.directObservations.value.isEmpty())
        assertThrows(IllegalStateException::class.java) { store.recordDirectObservation(secondary, oldSecondary) }
    }

    @Test fun motionInvalidationRejectsBothLateCameraResults() {
        val store = PointingRepository(context())
        val primary = store.captureRevision("primary"); val secondary = store.captureRevision("secondary")
        store.invalidateCurrentDirections()
        assertFalse(store.isCurrent(primary)); assertFalse(store.isCurrent(secondary))
        assertTrue(store.isCurrent(store.captureRevision("primary")))
    }

    @Test fun installationChangeRejectsCalibrationSaveBeforeEvidenceIsWritten() {
        val context = context(); val store = PointingRepository(context)
        val pairs = listOf(pair("1", 10.0), pair("2", 70.0))
        val calibration = OpticalCalibrationFitter.fit(pairs[0], pairs[1])
        val revisions = listOf(store.captureRevision("primary"), store.captureRevision("phone"))
        store.invalidateOpticalPath("primary", "telescope_changed")
        assertThrows(IllegalStateException::class.java) { store.saveValidated(calibration, pairs, expectedRevisions = revisions) }
        assertTrue(store.relationships.value.isEmpty())
        assertEquals(0, File(context.filesDir, "pointing").listFiles()!!.count { it.name.startsWith("evidence-") })
    }

    @Test fun movingToAnotherDirectionDoesNotInvalidateCalibrationTrainingInstallation() {
        val store = PointingRepository(context())
        val pairs = listOf(pair("1", 10.0), pair("2", 70.0))
        val calibration = OpticalCalibrationFitter.fit(pairs[0], pairs[1])
        val revisions = listOf(store.captureRevision("primary"), store.captureRevision("phone"))
        store.invalidateCurrentDirections()
        assertFalse(revisions.all(store::isCurrent))
        assertTrue(revisions.all(store::isSameInstallation))
        store.saveValidated(calibration, pairs, expectedRevisions = revisions)
        assertEquals(calibration, store.relationships.value.single())
    }

    @Test fun legacyFullPoseWithoutPhysicalReferenceIdentityIsNotRestoredAsValid() {
        val pairs = listOf(pair("1", 10.0), pair("2", 70.0))
        val calibration = OpticalCalibrationFitter.fit(pairs[0], pairs[1])
            .copy(targetPoseInSource = SkyPose.fromSolution(SkyDirection(0.0, 0.0), 0.0))
        val legacy = CalibrationJson.encode(calibration).apply { remove("targetAcquisitionDeviceId") }
        val restored = CalibrationJson.decode(legacy)
        assertFalse(restored.valid)
        assertEquals("missing_reference_camera_identity", restored.invalidReason)
    }
}
