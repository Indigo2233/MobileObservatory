package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.mount.MountAccessMode
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountMotionType
import com.indigo.mobileobservatory.mount.MountSlewRate
import com.indigo.mobileobservatory.ui.screens.CameraScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real capture workspace and VM, using only the in-process virtual devices. */
class CaptureMountWorkspaceInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: CameraViewModel
    private var originalPrefs: Map<String, *> = emptyMap<String, Any>()
    private val fontScale = mutableStateOf(1f)
    private val night = mutableStateOf(false)

    @Before fun showCaptureWorkspace() {
        val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        originalPrefs = prefs.all
        prefs.edit().putBoolean("update_auto_check", false).putString("mount_access_mode", "READ_WRITE").commit()
        store = ViewModelStore()
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                MobileObservatoryTheme(redNightMode = night.value) {
                    Surface(Modifier.fillMaxSize()) { CameraScreen(vm) }
                }
            }
        }
        compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
    }

    @After fun close() {
        instrumentation.runOnMainSync { store.clear() }
        val edit = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit().clear()
        originalPrefs.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Float -> edit.putFloat(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        edit.commit()
    }

    private fun connect() {
        compose.runOnIdle { vm.startVirtualObservation() }
        compose.waitUntil(20_000) { vm.virtualObservation.value != null || vm.virtualObservationMessage.value.isNotEmpty() }
        assertNotNull(vm.virtualObservationMessage.value, vm.virtualObservation.value)
        compose.waitUntil(15_000) { vm.previewBitmap.value != null }
        compose.onNodeWithTag("capture-mount-pad").assertIsDisplayed()
    }

    private fun togglePad() {
        compose.onNodeWithContentDescription(app.getString(R.string.camera_preview_tools)).performClick()
        compose.onNodeWithTag("capture-mount-pad-toggle").performClick()
    }

    @Test fun connectedMountShowsThePadAndOverflowSwitchPreservesPreviewSize() {
        compose.onNodeWithTag("capture-mount-pad").assertDoesNotExist()
        connect()
        val preview = compose.onNodeWithTag("capture-preview").getUnclippedBoundsInRoot()
        togglePad()
        compose.onNodeWithTag("capture-mount-pad").assertDoesNotExist()
        assertEquals(preview, compose.onNodeWithTag("capture-preview").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("workspace-EQUIPMENT").performClick()
        compose.onNodeWithTag("workspace-CAPTURE").performClick()
        compose.onNodeWithTag("capture-mount-pad").assertDoesNotExist()
        togglePad()
        compose.onNodeWithTag("capture-mount-pad").assertIsDisplayed()
        compose.runOnIdle { vm.stopVirtualObservation() }
        compose.waitUntil(10_000) { vm.virtualObservation.value == null && !vm.mountBusy.value }
        compose.onNodeWithTag("capture-mount-pad").assertDoesNotExist()
        connect()
        compose.onNodeWithTag("capture-mount-pad").assertIsDisplayed()
    }

    @Test fun heldMoveAndSpeedSelectionUseTheExistingMountStateAndReadOnlyDisablesControls() {
        connect()
        compose.onNodeWithTag("capture-mount-west").performTouchInput { down(center) }
        compose.waitUntil(5000) { vm.mountMotionState.value.type == MountMotionType.MANUAL }
        compose.onAllNodesWithText("STOP").assertCountEquals(0)
        compose.onNodeWithTag("capture-mount-west").performTouchInput { up() }
        compose.waitUntil(5000) { !vm.mountMotionState.value.isActive }
        compose.onNodeWithTag("capture-mount-speed").performClick()
        compose.onNodeWithTag("capture-mount-rate-RATE_2X").performClick()
        compose.waitUntil(5000) { vm.mountSlewRate.value == MountSlewRate.RATE_2X }
        compose.runOnIdle { vm.setMountAccessMode(MountAccessMode.READ_ONLY) }
        compose.onNodeWithTag("capture-mount-north").assertIsNotEnabled()
        compose.onNodeWithTag("capture-mount-speed").assertIsNotEnabled()
    }

    @Test fun navigationWhileHoldingStopsTheMoveAndLandscapeLargeFontKeepsThePadCompact() {
        connect()
        compose.onNodeWithTag("capture-mount-north").performTouchInput { down(center) }
        compose.waitUntil(5000) { vm.mountMotionState.value.type == MountMotionType.MANUAL }
        compose.onNodeWithTag("workspace-EQUIPMENT").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { !vm.mountMotionState.value.isActive }
        compose.onNodeWithTag("workspace-CAPTURE").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("capture-mount-north").performTouchInput { cancel() }
        compose.runOnIdle {
            fontScale.value = 2f
            night.value = true
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.onNodeWithTag("capture-mount-pad").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("capture-mount-pad").getUnclippedBoundsInRoot()
        assertTrue("Pad is wider than its compact layout", bounds.width.value <= 184f)
        assertTrue("Pad is taller than its compact layout", bounds.height.value <= 160f)
        MountDirection.entries.forEach { direction ->
            val button = compose.onNodeWithTag("capture-mount-${direction.name.lowercase()}")
            button.performTouchInput { down(center) }
            compose.waitUntil(5000) { vm.mountMotionState.value.manualDirection == direction }
            button.performTouchInput { up() }
            compose.waitUntil(5000) { !vm.mountMotionState.value.isActive }
        }
        compose.onNodeWithTag("capture-mount-speed").performClick()
        compose.onNodeWithTag("capture-mount-rate-RATE_MAX").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.mountSlewRate.value == MountSlewRate.RATE_MAX }
    }

    @Test fun automaticGotoDisablesDirectionsAndFocusAssistLeavesRoomForThePad() {
        connect()
        compose.runOnIdle {
            val position = requireNotNull(vm.mountCoordinates.value)
            assertTrue(vm.startPrecisionGoto("pad gating", position.raHours, position.decDeg, "JNOW"))
        }
        compose.onNodeWithTag("capture-mount-north").assertIsNotEnabled()
        compose.onNodeWithTag("capture-mount-speed").assertIsNotEnabled()
        compose.runOnIdle { vm.stopMountMotion() }
        compose.waitUntil(10_000) { !vm.mountBusy.value && !vm.mountMotionState.value.isActive }
        compose.onNodeWithTag("capture-mount-north").assertIsEnabled()
        compose.runOnIdle { vm.toggleFocusAssist() }
        compose.onNodeWithTag("capture-focus-assist").assertIsDisplayed()
        val pad = compose.onNodeWithTag("capture-mount-pad").getUnclippedBoundsInRoot()
        val focus = compose.onNodeWithTag("capture-focus-assist").getUnclippedBoundsInRoot()
        assertTrue("Direction pad overlaps focus controls", pad.right <= focus.left)
    }
}
