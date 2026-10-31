package com.indigo.mobileobservatory.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.MainActivity
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.mount.MountMotionType
import com.indigo.mobileobservatory.mount.PrecisionGotoPhase
import com.indigo.mobileobservatory.polar.PolarSamplePhase
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual Activity, shell, STOP handlers, ViewModel, preview workers, camera and mount adapters.
 * All devices belong to the in-process observatory; physical USB devices are never opened. */
class PrecisionGotoStopCrashInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    private val vm get() = ViewModelProvider(compose.activity)[CameraViewModel::class.java]
    private var savedExposure: Float? = null

    @Before fun connectVirtualDevices() {
        savedExposure = if (prefs.contains("precision_exposure_us")) prefs.getFloat("precision_exposure_us", 0f) else null
        prefs.edit().putFloat("precision_exposure_us", 5_000_000f).commit()
        compose.runOnIdle { vm.startVirtualObservation() }
        compose.waitUntil(25_000) { vm.virtualObservation.value != null || vm.virtualObservationMessage.value.isNotEmpty() }
        assertNotNull(vm.virtualObservationMessage.value, vm.virtualObservation.value)
        compose.waitUntil(15_000) { vm.guideDisplayFrame.value?.stars?.isNotEmpty() == true }
        compose.onNodeWithTag("workspace-STAR_MAP").performClick().assertIsSelected()
    }

    @After fun cleanup() {
        compose.runOnIdle { vm.stopVirtualObservation() }
        prefs.edit().also { edit ->
            savedExposure?.let { edit.putFloat("precision_exposure_us", it) } ?: edit.remove("precision_exposure_us")
        }.commit()
    }

    private fun beginCentering() {
        compose.runOnIdle {
            val position = requireNotNull(vm.mountCoordinates.value)
            assertTrue(vm.startPrecisionGoto("STOP regression", position.raHours + .01, position.decDeg, "JNOW"))
        }
    }

    private fun stopFromCenteringPanel() {
        val stopInDialog = hasText(context.getString(R.string.stop)) and hasAnyAncestor(isDialog())
        try {
            compose.waitUntil(5000) { compose.onAllNodes(stopInDialog).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Exception) {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            val tree = roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString(maxDepth = 12) }
            throw AssertionError("Panel STOP missing: ${vm.precisionGotoProgress.value}; motion=${vm.mountMotionState.value}\n$tree", failure)
        }
        val buttons = compose.onAllNodes(stopInDialog)
        buttons.assertCountEquals(1)
        compose.onNodeWithTag("polar-preview-stop").assertDoesNotExist()
        compose.onNodeWithTag("precision-goto-stop").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            vm.precisionGotoProgress.value.phase == PrecisionGotoPhase.CANCELLED && !vm.mountMotionState.value.isActive
        }
        compose.waitForIdle()
        assertFalse(compose.activity.isFinishing || compose.activity.isDestroyed)
        assertEquals(PrecisionGotoPhase.CANCELLED, vm.precisionGotoProgress.value.phase)
    }

    private fun closePanelAndCheckNavigation() {
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        listOf("CAPTURE", "EQUIPMENT", "STAR_MAP").forEach { workspace ->
            compose.onNodeWithTag("workspace-$workspace").performClick().assertIsSelected()
        }
    }

    @Test fun repeatedStopWhileSettlingKeepsTheActualActivityResponsive() {
        repeat(4) {
            beginCentering()
            compose.waitUntil(15_000) { vm.precisionGotoProgress.value.phase == PrecisionGotoPhase.SETTLING }
            stopFromCenteringPanel()
            closePanelAndCheckNavigation()
        }
    }

    @Test fun repeatedStopDuringFiveSecondExposureAndDuplicateStopsDoNotCrash() {
        repeat(4) {
            beginCentering()
            compose.waitUntil(20_000) { vm.precisionSamples.samples.value[0].phase == PolarSamplePhase.CAPTURING }
            stopFromCenteringPanel()
            compose.runOnIdle { repeat(3) { vm.stopMountMotion() } }
            compose.waitUntil(10_000) { !vm.mountBusy.value && !vm.mountMotionState.value.isActive }
            assertEquals(PolarSamplePhase.CANCELLED, vm.precisionSamples.samples.value[0].phase)
            closePanelAndCheckNavigation()
        }
    }

    @Test fun closingThePanelKeepsGlobalStopAndProgressCanBeReopened() {
        beginCentering()
        compose.waitUntil(20_000) { vm.precisionSamples.samples.value[0].phase == PolarSamplePhase.CAPTURING }
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        compose.onNodeWithTag("precision-goto-stop").assertDoesNotExist()
        compose.onNodeWithText("STOP").assertIsDisplayed()
        compose.onNodeWithTag("precision-goto-details").assertIsDisplayed().performClick()
        compose.onNodeWithTag("precision-goto-stop").assertIsDisplayed()
        compose.onNodeWithTag("polar-preview-stop").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        compose.onNodeWithText("STOP").performClick()
        compose.waitUntil(10_000) {
            vm.precisionGotoProgress.value.phase == PrecisionGotoPhase.CANCELLED && !vm.mountMotionState.value.isActive
        }
        assertFalse(compose.activity.isFinishing || compose.activity.isDestroyed)
    }

    @Test fun stopDuringSolvingRetainsTheImageAndTheNextCenteringCanFinish() {
        val session = requireNotNull(vm.virtualObservation.value)
        session.solveLatencyMs = 60_000
        beginCentering()
        compose.waitUntil(30_000) {
            val progress = vm.precisionGotoProgress.value
            if (progress.phase == PrecisionGotoPhase.FAILED) throw AssertionError("Centering failed before cancellation: $progress; sample=${vm.precisionSamples.samples.value[0]}")
            vm.precisionSamples.samples.value[0].phase == PolarSamplePhase.SOLVING
        }
        val capturedFile = requireNotNull(vm.precisionSamples.samples.value[0].file)
        stopFromCenteringPanel()
        assertTrue(capturedFile.isFile)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("polar-main-preview").assertIsDisplayed()
        closePanelAndCheckNavigation()
        session.solveLatencyMs = 200
        beginCentering()
        compose.waitUntil(30_000) { !vm.precisionGotoProgress.value.isActive }
        assertEquals(vm.precisionGotoProgress.value.message, PrecisionGotoPhase.SUCCEEDED, vm.precisionGotoProgress.value.phase)
    }

    @Test fun repeatedRaAndHomeStopsKeepTheEquipmentPageResponsive() {
        compose.onNodeWithTag("workspace-EQUIPMENT").performClick()
        repeat(12) {
            compose.runOnIdle { vm.moveMountRaBy(15.0, true, 3.0) }
            compose.waitUntil(5000) { vm.mountMotionState.value.type == MountMotionType.RA_MOVE }
            compose.onNodeWithText("STOP").performClick()
            compose.waitUntil(10_000) { !vm.mountBusy.value && !vm.mountMotionState.value.isActive }
            compose.runOnIdle { vm.goMountHome() }
            compose.waitUntil(5000) { vm.mountMotionState.value.type == MountMotionType.HOME }
            compose.onNodeWithText("STOP").performClick()
            compose.waitUntil(10_000) { !vm.mountBusy.value && !vm.mountMotionState.value.isActive }
            compose.onNodeWithTag("workspace-EQUIPMENT").assertIsSelected()
            assertFalse(compose.activity.isFinishing || compose.activity.isDestroyed)
        }
    }
}
