package com.indigo.mobileobservatory.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.indigo.mobileobservatory.MainActivity
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.guide.GuideCalibrationState
import com.indigo.mobileobservatory.polar.PolarSamplePhase
import com.indigo.mobileobservatory.polar.PolarSampleSession
import com.indigo.mobileobservatory.ui.components.RecordLimit
import com.indigo.mobileobservatory.ui.components.RecordLimitType
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.ui.viewmodel.PolarSamplesViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Activity, device adapters, preview pipeline and production workflow, without external equipment. */
class VirtualObservationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[CameraViewModel::class.java]
    private val virtualPrefs get() = compose.activity.getSharedPreferences("virtual_observation", Context.MODE_PRIVATE)
    private var savedVirtualPrefs: Map<String, *> = emptyMap<String, Any>()

    @Before fun prepareVirtualSettings() {
        savedVirtualPrefs = virtualPrefs.all
        virtualPrefs.edit().clear().commit()
    }

    @After fun stop() {
        compose.runOnIdle { vm.stopVirtualObservation() }
        virtualPrefs.edit().clear().also { edit ->
            savedVirtualPrefs.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
        }.commit()
    }

    private fun mediaFiles() = listOf("captures", "recordings").flatMap { dir ->
        compose.activity.getExternalFilesDir(dir)?.walkTopDown()?.filter { it.isFile }
            ?.map { it.absolutePath to it.length() }?.toList().orEmpty()
    }.toSet()

    private fun connect() {
        compose.onNodeWithTag("workspace-EQUIPMENT").performClick()
        compose.onNodeWithTag("virtual-start").performScrollTo().performClick()
        compose.waitUntil(20_000) { vm.virtualObservation.value != null || vm.virtualObservationMessage.value.isNotEmpty() }
        assertNotNull(vm.virtualObservationMessage.value, vm.virtualObservation.value)
        compose.onNodeWithTag("virtual-banner").assertIsDisplayed()
        compose.waitUntil(15_000) { vm.guideDisplayFrame.value?.stars?.isNotEmpty() == true }
    }

    @Test fun virtualPreviewControlsAndNavigationRemainResponsive() {
        connect()
        val session = requireNotNull(vm.virtualObservation.value)
        compose.onNodeWithTag("virtual-controls").performClick()
        compose.onNodeWithTag("virtual-stars").performClick()
        assertFalse(session.model.starsVisible)
        compose.onNodeWithTag("virtual-stars").performClick()
        assertTrue(session.model.starsVisible)
        compose.onNodeWithTag("virtual-controls").performClick()
        listOf("CAPTURE", "TOOLS", "EQUIPMENT").forEach { workspace ->
            compose.onNodeWithTag("workspace-$workspace").performClick().assertIsSelected()
        }
        compose.onNodeWithTag("virtual-stop").performClick()
        compose.waitUntil(10_000) { vm.virtualObservation.value == null }
        compose.onNodeWithTag("virtual-start").assertIsEnabled()
    }

    @Test fun virtualDevicesCalibrateAndGuideThenStopWithoutHardware() {
        connect()
        compose.runOnIdle { vm.setGuideExposure(200_000f) }
        compose.waitUntil(10_000) { vm.guideExposureUs.value == 200_000f }
        compose.onNodeWithTag("workspace-TOOLS").performClick()
        compose.onNodeWithTag("tool-guide").performScrollTo().performClick()
        compose.onNodeWithTag("guide-auto-select").performClick()
        compose.onNodeWithTag("guide-start-stop").performClick()
        compose.waitUntil(180_000) { vm.guideRunning.value || vm.guideCalibrationState.value == GuideCalibrationState.FAILED }
        assertTrue(vm.guideStatus.value, vm.guideRunning.value)
        assertNotNull(vm.guideCalibration.value)
        compose.onNodeWithTag("guide-image").assertIsDisplayed()
        compose.onNodeWithTag("guide-start-stop").performClick()
        compose.waitUntil(10_000) { !vm.guideRunning.value && !vm.guideCalibrating.value }
        compose.onNodeWithTag("virtual-stop").performClick()
        compose.waitUntil(10_000) { vm.virtualObservation.value == null && vm.guideCameraManager.activeCamera == null }
    }

    @Test fun polarWorkflowRetriesAndUpdatesAfterVirtualKnobAdjustment() {
        val filesBefore = mediaFiles()
        connect()
        val session = requireNotNull(vm.virtualObservation.value)
        compose.onNodeWithTag("virtual-controls").performClick()
        compose.onNodeWithTag("virtual-polar-preset").performClick()
        compose.onNodeWithTag("virtual-controls").performClick()
        assertEquals(.8, session.model.poleError().first, .001)
        assertEquals(.6, session.model.poleError().second, .001)
        compose.onNodeWithTag("workspace-TOOLS").performClick()
        compose.onNodeWithTag("tool-polar").performScrollTo().performClick()
        session.model.failedSolvesRemaining = 2
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        val samples = ViewModelProvider(compose.activity)["virtual-polar-${System.identityHashCode(session)}", PolarSamplesViewModel::class.java]
        compose.waitUntil(90_000) { samples.adjustment != null }
        val before = requireNotNull(samples.adjustment).current.totalErrorDeg
        val oldSequence = samples.session.samples.value[3].displaySequence
        session.model.adjustPole(-.4, -.3)
        compose.waitUntil(15_000) { samples.session.samples.value[3].displaySequence > oldSequence &&
            requireNotNull(samples.adjustment).current.totalErrorDeg < before * .8 }
        assertTrue(requireNotNull(samples.adjustment).current.totalErrorDeg < before * .8)
        compose.onNodeWithTag("polar-stop").performClick()
        compose.onNodeWithTag("virtual-banner").assertIsDisplayed()
        assertEquals("Injected solve failures must stay out of the photo library", filesBefore, mediaFiles())
    }

    @Test fun virtualPhotosAndRecordingsExerciseControlsWithoutSavingFiles() {
        val filesBefore = mediaFiles()
        connect()
        val session = requireNotNull(vm.virtualObservation.value)
        compose.runOnIdle { vm.setExposure(200_000f) }
        compose.waitUntil(10_000) { vm.exposureUs.value == 200_000f && session.primary.isCapturing.value }
        val framesAfterSettings = session.primary.framesEmitted
        compose.waitUntil(10_000) { session.primary.framesEmitted > framesAfterSettings }
        listOf("FITS", "JPG").forEach { format ->
            compose.runOnIdle {
                vm.setCaptureFormat(format)
                vm.capture()
                assertEquals(compose.activity.getString(R.string.sim_camera_capture_done), vm.statusMessage.value)
            }
        }
        listOf("MP4", "SER", "PSER", "FITS").forEach { format ->
            compose.runOnIdle {
                vm.setRecordFormat(format)
                vm.setRecordLimit(RecordLimit(RecordLimitType.FRAMES, 2))
                vm.startRecording()
                assertTrue(vm.statusMessage.value, vm.isRecording.value)
            }
            compose.waitUntil(15_000) { !vm.isRecording.value && vm.recordingFrameCount.value >= 2 }
            assertEquals(0L, vm.recordingBytes.value)
            assertNull(vm.getActiveRecordingFile())
        }
        compose.runOnIdle {
            val queue = vm.javaClass.getDeclaredField("recordWriteQueue").apply { isAccessible = true }.get(vm) as java.util.Queue<*>
            assertTrue("Virtual frames must not enter the file-writer queue", queue.isEmpty())
            vm.setRecordLimit(RecordLimit(RecordLimitType.NONE, 0))
            vm.startRecording()
            assertTrue(vm.isRecording.value)
            vm.togglePreviewPause()
        }
        compose.waitUntil(10_000) { !vm.isRecording.value && !session.primary.isCapturing.value }
        assertFalse(vm.scienceCaptureReady.value)
        assertNull(session.primary.scienceCaptureGeneration)
        assertEquals(filesBefore, mediaFiles())
    }

    @Test fun polarSolveCanBeAbandonedAndRetriedUsingTheSameImage() {
        val filesBefore = mediaFiles()
        connect()
        val session = requireNotNull(vm.virtualObservation.value)
        session.solveLatencyMs = 60_000
        compose.onNodeWithTag("workspace-TOOLS").performClick()
        compose.onNodeWithTag("tool-polar").performScrollTo().performClick()
        val samples = ViewModelProvider(compose.activity)["virtual-polar-${System.identityHashCode(session)}", PolarSamplesViewModel::class.java]
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(20_000) { samples.session.samples.value[0].phase == PolarSamplePhase.SOLVING }
        val captured = requireNotNull(samples.session.samples.value[0].file)
        val position = session.model.coordinates()
        compose.onNodeWithTag("polar-preview-stop")
            .assert(hasText(compose.activity.getString(R.string.polar_abandon_solve_retry))).performClick()
        compose.waitUntil(10_000) { samples.session.samples.value[0].phase == PolarSamplePhase.CANCELLED }
        assertTrue(captured.isFile)
        session.solveLatencyMs = 200
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("polar-retry-current") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("polar-retry-current").performScrollTo().performClick()
        compose.waitUntil(15_000) { samples.session.samples.value[0].phase == PolarSamplePhase.COMPLETE }
        assertEquals(captured, samples.session.samples.value[0].file)
        assertEquals(position, session.model.coordinates())
        assertTrue(samples.session.samples.value.drop(1).all { it.phase == PolarSamplePhase.EMPTY })
        assertEquals(filesBefore, mediaFiles())
        assertFalse(compose.activity.isFinishing || compose.activity.isDestroyed)
    }

    @Test fun virtualPolarExposureOwnsTheCameraUntilItIsCancelled() {
        connect()
        val session = requireNotNull(vm.virtualObservation.value)
        compose.onNodeWithTag("workspace-TOOLS").performClick()
        compose.onNodeWithTag("tool-polar").performScrollTo().performClick()
        val samples = ViewModelProvider(compose.activity)["virtual-polar-${System.identityHashCode(session)}", PolarSamplesViewModel::class.java]
        compose.onNodeWithTag("polar-exposure-seconds").performScrollTo().performTextReplacement("5")
        compose.onNodeWithTag("polar-test-capture").performClick()
        compose.waitUntil(10_000) { vm.previewPaused.value && !session.primary.isCapturing.value }
        compose.runOnIdle { vm.togglePreviewPause() }
        assertTrue("The preview must remain paused during a polar exposure", vm.previewPaused.value)
        compose.onNodeWithTag("polar-stop").performScrollTo().performClick()
        compose.waitUntil(10_000) { samples.session.samples.value[PolarSampleSession.PREVIEW_INDEX].phase == PolarSamplePhase.CANCELLED }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("polar-test-capture") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("polar-test-capture").performScrollTo().assertIsEnabled()
        compose.runOnIdle { vm.togglePreviewPause() }
        compose.waitUntil(10_000) { !vm.previewPaused.value && session.primary.isCapturing.value }
    }

    @Test fun exitingDuringAHiddenPolarSolveClosesTheOldSessionBeforeReconnect() {
        val filesBefore = mediaFiles()
        connect()
        val previous = requireNotNull(vm.virtualObservation.value)
        previous.solveLatencyMs = 60_000
        compose.onNodeWithTag("workspace-TOOLS").performClick()
        compose.onNodeWithTag("tool-polar").performScrollTo().performClick()
        val samples = ViewModelProvider(compose.activity)["virtual-polar-${System.identityHashCode(previous)}", PolarSamplesViewModel::class.java]
        compose.onNodeWithTag("polar-run").performScrollTo().performClick()
        compose.waitUntil(20_000) { samples.session.samples.value[0].phase == PolarSamplePhase.SOLVING }
        val image = requireNotNull(samples.session.samples.value[0].file)
        compose.onNodeWithTag("workspace-CAPTURE").performClick()
        assertEquals(PolarSamplePhase.SOLVING, samples.session.samples.value[0].phase)
        compose.onNodeWithTag("virtual-stop").performClick()
        compose.waitUntil(15_000) {
            vm.virtualObservation.value == null && vm.cameraManager.activeCamera == null &&
                vm.guideCameraManager.activeCamera == null && vm.mountCoordinates.value == null &&
                !vm.mountBusy.value && !vm.mountMotionState.value.isActive &&
                samples.session.samples.value[0].phase == PolarSamplePhase.CANCELLED
        }
        assertFalse(previous.ready)
        assertFalse(previous.primary.isOpen.value || previous.guide.isOpen.value)
        assertFalse(image.exists())
        val framesAtExit = previous.guide.framesEmitted
        connect()
        assertNotSame(previous, vm.virtualObservation.value)
        assertEquals(framesAtExit, previous.guide.framesEmitted)
        assertTrue(requireNotNull(vm.virtualObservation.value).ready)
        assertEquals(filesBefore, mediaFiles())
        assertFalse(compose.activity.isFinishing || compose.activity.isDestroyed)
    }

    @Test fun precisionGotoUsesTheVirtualCaptureAndSolver() {
        connect()
        val current = requireNotNull(vm.mountCoordinates.value)
        compose.runOnIdle { assertTrue(vm.startPrecisionGoto("Virtual target", current.raHours + .01, current.decDeg, "JNOW")) }
        compose.waitUntil(45_000) { !vm.precisionGotoProgress.value.isActive }
        assertEquals(vm.precisionGotoProgress.value.message, com.indigo.mobileobservatory.mount.PrecisionGotoPhase.SUCCEEDED,
            vm.precisionGotoProgress.value.phase)
        assertTrue(requireNotNull(vm.precisionGotoProgress.value.errorArcmin) <= 1.0)
    }
}
