package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.CameraInfo
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.camera.GainCapability
import kotlinx.coroutines.flow.MutableStateFlow
import com.indigo.mobileobservatory.stacking.StackMode
import com.indigo.mobileobservatory.ui.screens.CameraScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real shell and VM, no camera or mount connection, no simulated calibration. */
@RunWith(AndroidJUnit4::class)
class ObservatoryWorkflowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: CameraViewModel
    private lateinit var restoration: StateRestorationTester
    private var originalAutoCheck: Boolean? = null
    private val fontScale = mutableStateOf(1f)
    private val redNight = mutableStateOf(false)

    @Before fun startRealShell() {
        val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        originalAutoCheck = if (prefs.contains("update_auto_check")) prefs.getBoolean("update_auto_check", true) else null
        // A release notification would be unrelated to the workflow under test.
        prefs.edit().putBoolean("update_auto_check", false).commit()
        store = ViewModelStore()
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
        }
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = fontScale.value)) {
                MobileObservatoryTheme(redNightMode = redNight.value) {
                    CameraScreen(vm, redNightMode = redNight.value, onRedNightModeChange = { redNight.value = it })
                }
            }
        }
        workspace("CAPTURE").assertIsDisplayed().assertIsSelected()
    }

    @After fun closeShell() {
        instrumentation.runOnMainSync { store.clear() }
        val edit = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit()
        originalAutoCheck?.let { edit.putBoolean("update_auto_check", it) } ?: edit.remove("update_auto_check")
        edit.commit()
    }

    @Test fun fourWorkspacesReplaceMountAndToolsRootsAndCaptureHasNoUp() {
        listOf("STAR_MAP", "CAPTURE", "MEDIA", "EQUIPMENT").forEach { workspace(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription(app.getString(R.string.tab_mount)).assertDoesNotExist()
        compose.onNodeWithContentDescription(app.getString(R.string.cockpit_tab_tools)).assertDoesNotExist()
        compose.onNodeWithContentDescription(app.getString(R.string.back)).assertDoesNotExist()

        selectWorkspace("MEDIA")
        systemBack()
        workspace("CAPTURE").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun starPreparationAndPolarReturnOneLevelAtATime() {
        openPolarFromSky()
        compose.onNodeWithText(app.getString(R.string.three_point_polar_alignment)).assertIsDisplayed()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.wf_preparation)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.three_point_polar_alignment)).assertDoesNotExist()
        systemBack()
        workspace("STAR_MAP").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
        systemBack()
        workspace("CAPTURE").assertIsSelected()
    }

    @Test fun nativeMapCanConfigureTheSameOpticalSetupWithoutStellarium() {
        org.junit.Assume.assumeFalse(BuildConfig.STELLARIUM_ENABLED)
        selectWorkspace("STAR_MAP")
        compose.onNodeWithText(app.getString(R.string.wf_optics_title)).assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.fov_simulator_title)).assertIsDisplayed()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.fov_simulator_title)).assertDoesNotExist()
        workspace("STAR_MAP").assertIsSelected()
        compose.onNodeWithText(app.getString(R.string.wf_preparation)).assertIsDisplayed()
    }

    @Test fun pointingWizardConfiguresItsPrimaryTerminalInPlaceAndBackKeepsWizardContext() {
        selectWorkspace("STAR_MAP")
        compose.onNodeWithText(app.getString(if (BuildConfig.STELLARIUM_ENABLED)
            R.string.wf_star_map_preparation else R.string.wf_preparation)).performClick()
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).performClick()
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).assertIsDisplayed()
        val setup = compose.onAllNodes(hasText(app.getString(R.string.wf_optics_title)) or
            hasText(app.getString(R.string.wf_optics_confirmed))).onFirst()
        setup.performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.fov_simulator_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.star_map_train_secondary)).assertDoesNotExist()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.fov_simulator_title)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.wfp_setup)).assertIsDisplayed()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.wf_preparation)).assertIsDisplayed()
    }

    @Test fun settingsCoverPolarAndBackPreservesUnsavedFieldWithoutHiddenBackHandlerWinning() {
        openPolarFromSky()
        val latitude = hasSetTextAction() and hasText(app.getString(R.string.latitude_deg))
        compose.onNode(latitude).performScrollTo().performTextReplacement("31.234")
        // Latitude changes above do not write the persistent setting until the workflow acts.
        hideKeyboard()
        compose.onNodeWithContentDescription(app.getString(R.string.settings)).performClick()
        compose.onNodeWithText(app.getString(R.string.settings)).assertIsDisplayed()
        systemBack()
        compose.onNode(latitude).assertTextContains("31.234")
        compose.onNodeWithText(app.getString(R.string.three_point_polar_alignment)).assertIsDisplayed()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.wf_preparation)).assertIsDisplayed()
    }

    @Test fun polarDraftSurvivesWorkspaceSwitchAndSavedShellRestoration() {
        openPolarFromSky()
        val latitude = hasSetTextAction() and hasText(app.getString(R.string.latitude_deg))
        compose.onNode(latitude).performScrollTo().performTextReplacement("31.234")
        hideKeyboard()
        selectWorkspace("CAPTURE")
        selectWorkspace("STAR_MAP")
        compose.onNode(latitude).performScrollTo().assertTextContains("31.234")
        compose.onNodeWithText(app.getString(R.string.three_point_polar_alignment)).assertIsDisplayed()
        hideKeyboard()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(latitude).performScrollTo().assertTextContains("31.234")
        systemBack()
        compose.onNodeWithText(app.getString(R.string.wf_preparation)).assertIsDisplayed()
    }

    @Test fun deviceDraftGuardsWorkspaceRailKeepsPendingExitOnRestoreAndRemainsUsableAfterDiscard() {
        compose.runOnIdle {
            setFlow(vm.cameraManager, "_connectionState", ConnectionState.Connected(CameraInfo("WF draft camera", "ui-fixture", 1280, 960, 16)))
            setFlow(vm, "_gainCapability", GainCapability(min = 0f, max = 100f, defaultValue = 0f))
        }
        selectWorkspace("EQUIPMENT")
        compose.onNodeWithTag("equipment-camera").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.wf_device_settings)).performScrollTo().performClick()
        val originalGain = compose.onNodeWithTag("device-setting-gain").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        compose.onNodeWithTag("device-setting-gain").performScrollTo().performTextReplacement("42")
        hideKeyboard()
        workspace("CAPTURE").performClick()
        compose.onNodeWithText(app.getString(R.string.wf_edit_unsaved_title)).assertIsDisplayed()
        workspace("EQUIPMENT").assertIsSelected()
        compose.onNodeWithText(app.getString(R.string.wf_edit_keep)).performClick()
        compose.onNodeWithTag("device-setting-gain").assertTextContains("42")
        workspace("CAPTURE").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(app.getString(R.string.wf_edit_unsaved_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.wf_edit_discard)).performClick()
        workspace("CAPTURE").assertIsSelected()
        selectWorkspace("EQUIPMENT")
        compose.onNodeWithTag("device-setting-gain").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("device-setting-gain").assertTextContains(originalGain)
    }

    @Test fun settingsBackReturnsToEachOriginatingWorkspace() {
        listOf("CAPTURE", "MEDIA", "EQUIPMENT", "STAR_MAP").forEach { key ->
            selectWorkspace(key)
            compose.onNodeWithContentDescription(app.getString(R.string.settings)).performClick()
            compose.onNodeWithText(app.getString(R.string.settings)).assertIsDisplayed()
            systemBack()
            workspace(key).assertIsDisplayed().assertIsSelected()
            assertFalse(compose.activity.isFinishing)
        }
    }

    @Test fun savedStateRestoresPolarInputDraftUnderSettingsAndKeepsItsParentStack() {
        openPolarFromSky()
        val latitude = hasSetTextAction() and hasText(app.getString(R.string.latitude_deg))
        val longitude = hasSetTextAction() and hasText(app.getString(R.string.longitude_deg))
        compose.onNode(latitude).performScrollTo().performTextReplacement("31.234")
        compose.onNode(longitude).performTextReplacement("121.765")
        hideKeyboard()
        compose.onNodeWithContentDescription(app.getString(R.string.settings)).performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(app.getString(R.string.settings)).assertIsDisplayed()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.three_point_polar_alignment)).assertIsDisplayed()
        compose.onNode(latitude).assertTextContains("31.234")
        compose.onNode(longitude).assertTextContains("121.765")
        systemBack()
        compose.onNodeWithText(app.getString(R.string.wf_preparation)).assertIsDisplayed()
        systemBack()
        workspace("STAR_MAP").assertIsSelected()
        // Saved input and navigation do not restore a motor task or a scientific solve result.
        assertFalse(vm.mountMotionState.value.showsGlobalStop)
    }

    @Test fun polarDraftAndParentSurviveARealPortraitToLandscapeConfigurationChange() {
        openPolarFromSky()
        val latitude = hasSetTextAction() and hasText(app.getString(R.string.latitude_deg))
        compose.onNode(latitude).performScrollTo().performTextReplacement("32.456")
        hideKeyboard()
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        compose.onNode(latitude).assertTextContains("32.456")
        compose.onNodeWithText(app.getString(R.string.three_point_polar_alignment)).assertIsDisplayed()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.wf_preparation)).assertIsDisplayed()
        systemBack()
        workspace("STAR_MAP").assertIsSelected()
    }

    @Test fun filterAndFocuserStatusOpenTargetedSheetAndKeepImagesWorkspace() {
        selectWorkspace("MEDIA")
        listOf(R.string.filter_wheel, R.string.focuser).forEach { resource ->
            compose.onNodeWithContentDescription("${app.getString(resource)} status")
                .performScrollTo().performClick()
            compose.onNodeWithContentDescription(app.getString(R.string.close)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.wf_device_connect_hint)).assertIsDisplayed()
            // A disconnected device shows only its own connection action and no all-device tabs.
            workspace("EQUIPMENT").assertIsNotSelected()
            compose.onNodeWithText(app.getString(R.string.device_connections)).assertDoesNotExist()
            compose.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription(app.getString(R.string.close)).fetchSemanticsNodes().isEmpty()
            }
            workspace("MEDIA").assertIsSelected()
            compose.onNodeWithText(app.getString(R.string.media_library_title)).assertIsDisplayed()
        }
    }

    @Test fun mediaRootHasNoUpAndTrashSystemBackReturnsToListBeforeCapture() {
        selectWorkspace("MEDIA")
        compose.onNodeWithText(app.getString(R.string.media_library_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.back)).assertDoesNotExist()
        compose.onNodeWithContentDescription(app.getString(R.string.trash)).performClick()
        compose.onNodeWithContentDescription(app.getString(R.string.files)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.back)).assertIsDisplayed()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.media_library_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.back)).assertDoesNotExist()
        workspace("MEDIA").assertIsSelected()
        systemBack()
        workspace("CAPTURE").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun coveredEaaRetainsExpandedDetailsGroupAndReturnsToCapture() {
        compose.runOnIdle { vm.stacking.open(StackMode.LIVE) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(app.getString(R.string.stack_show_details)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.getString(R.string.stack_show_details)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.stack_hide_details)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.settings)).performClick()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.stack_hide_details)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.stack_show_details)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.stack_live)).assertIsDisplayed()
        systemBack()
        workspace("CAPTURE").assertIsSelected()
        compose.runOnIdle { assertNull(vm.stacking.destination.value) }
    }

    @Test fun noMountTargetCatalogCanEnterCalibrationAndReturnToTheSameTargetInBothMapBuilds() {
        selectWorkspace("STAR_MAP")
        if (BuildConfig.STELLARIUM_ENABLED) {
            compose.onNodeWithText(app.getString(R.string.target_library_title)).performClick()
        }
        compose.onNode(hasSetTextAction() and hasText(app.getString(R.string.target_library_search)))
            .performTextReplacement("M42")
        hideKeyboard()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(app.getString(R.string.target_library_guide)).fetchSemanticsNodes().isNotEmpty()
        }
        // A running rotation-vector feed continually drives arrow animations. Freeze the test
        // frame clock to inspect navigation without waiting for an intentionally live view to idle.
        compose.mainClock.autoAdvance = false
        try {
            compose.onAllNodesWithText(app.getString(R.string.target_library_guide)).onFirst().performClick()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText(app.getString(R.string.wf_push_calibrate)).assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText(app.getString(R.string.wfp_setup)).assertIsDisplayed()
            instrumentation.runOnMainSync { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText(app.getString(R.string.wf_push_calibrate)).assertIsDisplayed()
            compose.onNodeWithTag("push-task-target").assertTextContains("M 42", substring = true).assertIsDisplayed()
            instrumentation.runOnMainSync { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(1_000)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        if (BuildConfig.STELLARIUM_ENABLED) {
            compose.onNode(hasSetTextAction() and hasText(app.getString(R.string.target_library_search))).assertTextContains("M42")
            systemBack()
        }
        workspace("STAR_MAP").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun preparationAndDeviceSheetRemainOperableInRedModeAtTwoHundredPercentFonts() {
        compose.runOnIdle { fontScale.value = 2f; redNight.value = true }
        selectWorkspace("STAR_MAP")
        compose.onNodeWithText(app.getString(if (BuildConfig.STELLARIUM_ENABLED)
            R.string.wf_star_map_preparation else R.string.wf_preparation)).assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.back)).assertIsDisplayed().performClick()
        workspace("STAR_MAP").assertIsSelected()

        compose.onNodeWithContentDescription("${app.getString(R.string.focuser)} status")
            .performScrollTo().performClick()
        compose.onNodeWithContentDescription(app.getString(R.string.close)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.scan)).performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(app.getString(R.string.wf_device_connect_hint)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(app.getString(R.string.close)).fetchSemanticsNodes().isEmpty()
        }
        workspace("STAR_MAP").assertIsSelected()
    }

    private fun workspace(key: String) = compose.onNodeWithTag("workspace-$key")

    @Suppress("UNCHECKED_CAST")
    private fun <T> setFlow(owner: Any, name: String, value: T) {
        (owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>).value = value
    }

    private fun selectWorkspace(key: String) {
        workspace(key).performClick()
        workspace(key).assertIsSelected()
    }

    private fun openPolarFromSky() {
        selectWorkspace("STAR_MAP")
        compose.onNodeWithText(app.getString(if (BuildConfig.STELLARIUM_ENABLED)
            R.string.wf_star_map_preparation else R.string.wf_preparation)).performClick()
        compose.onNodeWithText(app.getString(R.string.polar_alignment)).performClick()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
            compose.activity.currentFocus?.clearFocus()
        }
        compose.waitForIdle()
    }
}
