package com.indigo.mobileobservatory.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.indigo.mobileobservatory.MainActivity
import com.indigo.mobileobservatory.guide.GuideCalibrationState
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.ui.viewmodel.PolarSamplesViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Activity, device adapters, preview pipeline and production workflow, without external equipment. */
class VirtualObservationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[CameraViewModel::class.java]
    @After fun stop() { compose.runOnIdle { vm.stopVirtualObservation() } }

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
    }

    @Test fun precisionGotoUsesTheVirtualCaptureAndSolver() {
        connect()
        val current = requireNotNull(vm.mountCoordinates.value)
        compose.runOnIdle { assertTrue(vm.startPrecisionGoto("Virtual target", current.raHours + .01, current.decDeg, "JNOW")) }
        compose.waitUntil(45_000) { !vm.precisionGotoProgress.value.isActive }
        assertEquals(vm.precisionGotoProgress.value.message, com.indigo.mobileobservatory.mount.PrecisionGotoPhase.SUCCEEDED,
            vm.precisionGotoProgress.value.phase)
        assertTrue(requireNotNull(vm.precisionGotoProgress.value.errorArcmin) < 6.0)
    }
}
