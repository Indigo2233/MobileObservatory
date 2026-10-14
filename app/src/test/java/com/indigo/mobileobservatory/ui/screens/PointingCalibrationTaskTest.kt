package com.indigo.mobileobservatory.ui.screens

import com.indigo.mobileobservatory.pointing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PointingCalibrationTaskTest {
    private val source = OpticalIdentity("phone", "rear-2", "phone")
    private val target = OpticalIdentity("tube", "axis", "primary")
    private val geometry = CameraGeometry(1000, 800, parity = ImageParity.NORMAL, intrinsicsFingerprint = "physical-lens")
    private val offset = SkyVector(.3, -.2, .9).unit()
    private val paths = listOf("phone", "primary")

    private fun capture(id: String, ra: Double, errorDeg: Double = 0.0): PointingCalibrationCapture {
        val pose = SkyPose.fromSolution(SkyDirection(ra, 30.0), ra / 2)
        val stamp = ObservationStamp(1_700_000_000_000, 10_000, 1000, 5)
        val direction = pose.project(offset).direction()
        return PointingCalibrationCapture(CalibrationPair(
            OpticalObservation("source-$id", source, stamp, pose.forward.direction(), pose, geometry, .015,
                OpticalObservationKind.PHONE_PLATE),
            OpticalObservation("ref-$id", target, stamp, direction.copy(decDeg = direction.decDeg + errorDeg),
                null, null, .02, OpticalObservationKind.CENTERED_STAR)))
    }

    private class Store : CalibrationTaskStore {
        var installationRevision = 0L
        var version = 7L
        var savedPairs: List<CalibrationPair>? = null
        var savedValue: OpticalCalibration? = null
        override fun captureRevision(path: String) = PointingRepository.CaptureRevision(path, installationRevision, 0)
        override fun isSameInstallation(revision: PointingRepository.CaptureRevision) = revision.pathRevision == installationRevision
        override fun opticalVersion(pair: CalibrationPair) = version
        override fun handEyeVersion(observation: OpticalObservation) = 0L
        override fun save(value: OpticalCalibration, pairs: List<CalibrationPair>, handEye: HandEyeCalibration?,
                          revisions: List<PointingRepository.CaptureRevision>) {
            check(revisions.all(::isSameInstallation))
            check(value.version > version)
            savedPairs = pairs
            savedValue = value
            version = value.version
        }
    }

    @Test fun independentHoldoutFitsAndSavesTheSamePairEvidence() = runTest {
        val store = Store(); val task = PointingCalibrationTask(this)
        val training = capture("1", 20.0); val validation = capture("2", 80.0)
        task.sample(store, "lens-2", paths) { training }; runCurrent()
        assertNull(task.draft.value.fitted)
        task.sample(store, "lens-2", paths) { validation }; runCurrent()
        assertEquals(8L, task.draft.value.fitted!!.version)
        assertTrue(task.draft.value.fitted!!.axisInSource.angleDeg(offset) < 1e-8)
        assertTrue(task.save(store, "lens-2", "changed"))
        assertEquals(listOf(training.pair, validation.pair), store.savedPairs)
        assertFalse(task.save(store, "lens-2", "changed"))
    }

    @Test fun roughAlignmentDiscardsTrainingAndCannotBeSavedAsCalibration() = runTest {
        val store = Store(); val task = PointingCalibrationTask(this)
        task.sample(store, "lens-2", paths) { capture("1", 20.0) }; runCurrent()
        val rough = capture("rough", 80.0)
        task.sample(store, "lens-2", paths, alignmentOnly = true) { rough }; runCurrent()
        assertEquals(rough.pair, task.draft.value.alignmentPair)
        assertNull(task.draft.value.first)
        assertNull(task.draft.value.validation)
        assertFalse(task.save(store, "lens-2", "changed"))
        task.sample(store, "lens-2", paths) { capture("2", 120.0) }; runCurrent()
        assertNull(task.draft.value.fitted)
        assertNull(task.draft.value.alignmentPair)
    }

    @Test fun idleCancellationPreservesCompletedAndSavedResultsForRecreation() = runTest {
        val store = Store(); val task = PointingCalibrationTask(this)
        task.sample(store, "lens-2", paths) { capture("1", 20.0) }; runCurrent()
        task.sample(store, "lens-2", paths) { capture("2", 80.0) }; runCurrent()
        val fitted = task.draft.value.fitted
        task.cancel()
        assertSame(fitted, task.draft.value.fitted)
        assertTrue(task.save(store, "lens-2", "changed"))
        task.cancel()
        assertTrue(task.draft.value.saved)
        assertSame(fitted, task.draft.value.fitted)
        task.clearValidation()
        assertNull(task.draft.value.fitted)
        assertFalse(task.draft.value.saved)
        assertNotNull(task.draft.value.first)
    }

    @Test fun canceledUncooperativeCaptureCannotPublishOverTheNextDraft() = runTest {
        val store = Store(); val task = PointingCalibrationTask(this)
        val gate = CompletableDeferred<Unit>()
        task.sample(store, "old-lens", paths) {
            withContext(NonCancellable) { gate.await() }
            capture("stale", 20.0)
        }
        runCurrent()
        task.reset()
        val fresh = capture("fresh", 80.0)
        task.sample(store, "new-lens", paths) { fresh }
        runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(fresh.pair, task.draft.value.first)
        assertEquals("new-lens", task.draft.value.configurationKey)
        assertNull(task.draft.value.error)
        assertFalse(task.draft.value.busy)
    }

    @Test fun changedInstallationDuringCaptureAndBeforeSaveRejectsStaleResults() = runTest {
        val store = Store(); val task = PointingCalibrationTask(this)
        val gate = CompletableDeferred<Unit>()
        task.sample(store, "lens-2", paths) { gate.await(); capture("1", 20.0) }
        runCurrent(); store.installationRevision++; gate.complete(Unit); runCurrent()
        assertNull(task.draft.value.first)
        assertNotNull(task.draft.value.error)
        task.sample(store, "lens-2", paths) { capture("2", 20.0) }; runCurrent()
        task.sample(store, "lens-2", paths) { capture("3", 80.0) }; runCurrent()
        store.installationRevision++
        assertFalse(task.save(store, "lens-2", "changed"))
        assertEquals("changed", task.draft.value.error)
        assertNull(store.savedValue)
        assertNull(task.draft.value.fitted)
    }

    @Test fun changingLensOrMirrorModeCannotReuseTrainingEvenWithSameDevice() = runTest {
        val store = Store(); val task = PointingCalibrationTask(this)
        task.sample(store, "lens-2", paths) { capture("1", 20.0) }; runCurrent()
        var captured = false
        task.sample(store, "lens-3", paths) { captured = true; capture("2", 80.0) }; runCurrent()
        assertFalse(captured)
        assertNull(task.draft.value.first)
        task.sample(store, "lens-3", paths) { capture("3", 20.0) }; runCurrent()
        task.sample(store, "lens-3", paths, mirrored = true) { captured = true; capture("4", 80.0) }; runCurrent()
        assertFalse(captured)
        assertNull(task.draft.value.first)
    }

    @Test fun failedValidationCannotReuseAnEarlierSuccessfulResult() = runTest {
        val store = Store(); val task = PointingCalibrationTask(this)
        task.sample(store, "lens-2", paths) { capture("1", 20.0) }; runCurrent()
        task.sample(store, "lens-2", paths) { capture("2", 80.0) }; runCurrent()
        assertNotNull(task.draft.value.fitted)
        task.sample(store, "lens-2", paths) { capture("bad", 130.0, 2.0) }; runCurrent()
        assertNull(task.draft.value.fitted)
        assertNull(task.draft.value.validation)
        assertFalse(task.save(store, "lens-2", "changed"))
    }

    @Test fun automaticSamplingRequiresAllCapturesAndACompletedMotionOwner() = runTest {
        val store = Store(); val task = PointingCalibrationTask(this)
        var index = 0
        task.runAutomatic(store, "lens-2", paths, run = { take -> take() }) {
            index++; capture("auto-$index", if (index == 1) 20.0 else 80.0)
        }
        runCurrent()
        assertNull(task.draft.value.fitted)
        assertFalse(task.save(store, "lens-2", "changed"))
        index = 0
        task.runAutomatic(store, "lens-2", paths, run = { take -> take(); take(); error("STOP failed") }) {
            index++; capture("complete-$index", if (index == 1) 20.0 else 80.0)
        }
        runCurrent()
        assertNull(task.draft.value.fitted)
        assertEquals("STOP failed", task.draft.value.error)
        index = 0
        task.runAutomatic(store, "lens-2", paths, run = { take -> take(); take() }) {
            index++; capture("valid-$index", if (index == 1) 20.0 else 80.0)
        }
        runCurrent()
        assertNotNull(task.draft.value.fitted)
        assertTrue(task.save(store, "lens-2", "changed"))
    }

    @Test fun automaticFailurePropagatesToStopFurtherMovesAndTimeoutHasAnError() = runTest {
        val store = Store(); val task = PointingCalibrationTask(this)
        var calls = 0; var motionStopped = false
        task.runAutomatic(store, "lens-2", paths, run = { take ->
            try { repeat(2) { take() } } finally { motionStopped = true }
        }) {
            calls++
            error("capture failed")
        }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(motionStopped)
        assertNull(task.draft.value.fitted)
        task.sample(store, "lens-2", paths) { withTimeout(5) { awaitCancellation() } }
        advanceUntilIdle()
        assertNotNull(task.draft.value.error)
        assertFalse(task.draft.value.busy)
    }

    @Test fun selectedPathRevisionsMustActuallyCoverBothAcquiredOpticalPaths() = runTest {
        val store = Store(); val task = PointingCalibrationTask(this)
        task.sample(store, "lens-2", listOf("other", "primary")) { capture("1", 20.0) }
        runCurrent()
        assertNull(task.draft.value.first)
        assertNotNull(task.draft.value.error)
    }
}
