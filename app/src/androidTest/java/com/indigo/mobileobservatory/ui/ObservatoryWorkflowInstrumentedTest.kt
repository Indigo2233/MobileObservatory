package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.indigo.mobileobservatory.astro.AndroidOpticsPrefs
import com.indigo.mobileobservatory.astro.FovInstrumentMode
import com.indigo.mobileobservatory.astro.OpticsTrainConfig
import com.indigo.mobileobservatory.astro.OpticsTrainId
import com.indigo.mobileobservatory.astro.PointingOpticsConfiguration
import com.indigo.mobileobservatory.astro.PointingOpticsEndpoint
import com.indigo.mobileobservatory.astro.PointingOpticsSelection
import com.indigo.mobileobservatory.astro.StarMapOpticsPrefs
import com.indigo.mobileobservatory.astro.UserOpticsCatalog
import com.indigo.mobileobservatory.camera.CameraInfo
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.camera.GainCapability
import kotlinx.coroutines.flow.MutableStateFlow
import com.indigo.mobileobservatory.stacking.StackMode
import com.indigo.mobileobservatory.stacking.StackTarget
import com.indigo.mobileobservatory.ui.screens.CameraScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
    private var savedPointingPreferences: Map<String, *>? = null
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
                    androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                    CameraScreen(vm, redNightMode = redNight.value, onRedNightModeChange = { redNight.value = it })
                    }
                }
            }
        }
        workspace("CAPTURE").assertIsDisplayed().assertIsSelected()
    }

    @After fun closeShell() {
        instrumentation.runOnMainSync { store.clear() }
        val edit = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit()
        originalAutoCheck?.let { edit.putBoolean("update_auto_check", it) } ?: edit.remove("update_auto_check")
        savedPointingPreferences?.let { saved ->
            val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
            prefs.all.keys.filter(::isPointingPreference).forEach(edit::remove)
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> Unit
            } }
        }
        edit.commit()
    }

    @Test fun taskWorkspacesAndRestoredToolsHubKeepCaptureAsRootWithoutUp() {
        listOf("STAR_MAP", "CAPTURE", "MEDIA", "EQUIPMENT", "TOOLS").forEach { workspace(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription(app.getString(R.string.tab_mount)).assertDoesNotExist()
        compose.onNodeWithContentDescription(app.getString(R.string.back)).assertDoesNotExist()

        selectWorkspace("MEDIA")
        systemBack()
        workspace("CAPTURE").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun liveAndPostStackingShareSubjectTabsButKeepSeparateSelectionsAndReturnPaths() {
        selectWorkspace("MEDIA")
        compose.onNodeWithTag("media-import").assertIsDisplayed()
        captureWorkflowEvidence("media-actions")
        compose.onNodeWithTag("media-post-stack").assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.stack_offline_title)).assertIsDisplayed()
        compose.onNodeWithTag("stack-target-PLANETARY").assertIsSelected()
        compose.onNodeWithTag("stack-target-DEEP_SKY").performClick().assertIsSelected()
        captureWorkflowEvidence("post-deep-sky")
        compose.runOnIdle {
            org.junit.Assert.assertEquals(StackMode.PLANETARY, vm.stacking.destination.value)
            org.junit.Assert.assertEquals(StackTarget.DEEP_SKY, vm.stacking.offlineTarget.value)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("stack-target-DEEP_SKY").assertIsSelected()
        systemBack()
        workspace("MEDIA").assertIsSelected()
        selectWorkspace("CAPTURE")
        compose.onNodeWithText(app.getString(R.string.stack_live)).performClick()
        compose.onNodeWithTag("stack-target-DEEP_SKY").assertIsSelected()
        compose.onNodeWithTag("stack-target-PLANETARY").performClick().assertIsSelected()
        captureWorkflowEvidence("live-planetary")
        compose.runOnIdle {
            org.junit.Assert.assertEquals(StackMode.LIVE, vm.stacking.destination.value)
            org.junit.Assert.assertEquals(StackTarget.PLANETARY, vm.stacking.liveTarget.value)
            org.junit.Assert.assertEquals(StackTarget.DEEP_SKY, vm.stacking.offlineTarget.value)
        }
        systemBack()
        workspace("CAPTURE").assertIsSelected()
        selectWorkspace("MEDIA")
        compose.onNodeWithTag("media-post-stack").performClick()
        compose.onNodeWithTag("stack-target-DEEP_SKY").assertIsSelected()
    }

    @Test fun browsingAndCaptureWorkspacesAllowBothOrientations() {
        listOf("MEDIA", "EQUIPMENT", "STAR_MAP").forEach { key ->
            selectWorkspace(key)
            compose.runOnIdle {
                org.junit.Assert.assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, compose.activity.requestedOrientation)
            }
        }
        selectWorkspace("CAPTURE")
        compose.runOnIdle {
            org.junit.Assert.assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, compose.activity.requestedOrientation)
        }
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
        prepareUnconfirmedPhoneEyepiece()
        selectWorkspace("STAR_MAP")
        compose.onNodeWithText(app.getString(if (BuildConfig.STELLARIUM_ENABLED)
            R.string.wf_star_map_preparation else R.string.wf_preparation)).performClick()
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).performClick()
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).assertIsDisplayed()
        val setup = compose.onNodeWithTag("pointing-blocker-MAIN_OPTICS")
        setup.performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.fov_simulator_title)).assertIsDisplayed()
        compose.onNode(hasText(app.getString(R.string.star_map_train_secondary)) and hasAnyAncestor(isDialog())).assertDoesNotExist()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.fov_simulator_title)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).assertIsDisplayed()
        compose.onNodeWithTag("pointing-source").performScrollTo().assertIsDisplayed()
        captureWorkflowEvidence("pointing-camera-setup")
        systemBack()
        compose.onNodeWithText(app.getString(R.string.wf_preparation)).assertIsDisplayed()
    }

    @Test fun phoneEyepieceOpticsConfirmationClearsItsBlockerThroughTheActualShellAndViewModel() {
        prepareUnconfirmedPhoneEyepiece()
        selectWorkspace("STAR_MAP")
        compose.onNodeWithText(app.getString(if (BuildConfig.STELLARIUM_ENABLED)
            R.string.wf_star_map_preparation else R.string.wf_preparation)).performClick()
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).performClick()
        compose.onNodeWithText(app.getString(R.string.wfp_eyepiece)).assertIsSelected()
        compose.onNodeWithTag("pointing-locked").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("pointing-blocker-MAIN_OPTICS").performScrollTo().assertIsDisplayed()
        // This selected path does not need an external reference camera or ASTAP database.
        compose.onNodeWithTag("pointing-blocker-MAIN_CAMERA").assertDoesNotExist()
        compose.onNodeWithTag("pointing-blocker-DATABASE").assertDoesNotExist()
        captureWorkflowEvidence("pointing-blockers")
        compose.onNodeWithTag("pointing-options").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, app.getString(R.string.wfc_collapsed)))
        compose.onNodeWithTag("pointing-options-arrow", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("pointing-options").performClick().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, app.getString(R.string.wfc_expanded)))
        compose.onNodeWithTag("pointing-options-arrow", useUnmergedTree = true).assertIsDisplayed()
        captureWorkflowEvidence("pointing-advanced-expanded")
        compose.onNodeWithTag("pointing-options").performClick()
        compose.onNodeWithTag("pointing-blocker-MAIN_OPTICS").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.fov_simulator_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.wf_optics_use_setup)).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(app.getString(R.string.fov_simulator_title)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).assertIsDisplayed()
        compose.onNodeWithTag("pointing-blocker-MAIN_OPTICS").assertDoesNotExist()
        compose.onNodeWithTag("pointing-blocker-MAIN_CAMERA").assertDoesNotExist()
        compose.onNodeWithTag("pointing-blocker-DATABASE").assertDoesNotExist()
        compose.runOnIdle {
            val optics = vm.pointingOpticsConfiguration()
            assertTrue(OpticsTrainId.PRIMARY in optics.confirmedTrains)
            assertNotNull(optics.verificationField(PointingOpticsSelection(OpticsTrainId.PRIMARY, PointingOpticsEndpoint.EYEPIECE)))
            assertFalse(vm.connectionState.value is ConnectionState.Connected)
            assertFalse(vm.mountPointingCapabilities.value.goto)
        }
        // The emulator has no guaranteed manual phone lens; do not invent camera capabilities
        // or claim that clearing this optical blocker satisfies every acquisition prerequisite.
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
            compose.onNodeWithTag("pointing-source").assertIsDisplayed()
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

    @Test fun offlineSolveAndDatabaseRemainReachableFromMediaAndSkyWithTheirOwnBackPath() {
        selectWorkspace("MEDIA")
        compose.onNodeWithTag("media-plate-solve").performScrollTo().performClick()
        assertGradedDatabaseChoices()
        compose.onNodeWithTag("solve-database-D50").performClick().assertIsSelected()
        // Selecting a tier is local; these navigation tests do not download or delete data.
        systemBack()
        workspace("MEDIA").assertIsSelected()
        compose.onNodeWithTag("media-import").assertIsDisplayed()

        selectWorkspace("STAR_MAP")
        compose.onNodeWithText(app.getString(if (BuildConfig.STELLARIUM_ENABLED)
            R.string.wf_star_map_preparation else R.string.wf_preparation)).performClick()
        compose.onNodeWithText(app.getString(R.string.solve_and_offline_database)).performClick()
        assertGradedDatabaseChoices()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.solve_and_offline_database)).assertIsDisplayed()
        systemBack()
        workspace("STAR_MAP").assertIsSelected()
    }

    @Test fun captureOverflowKeepsOfflineSolvingAvailableWithoutCamera() {
        compose.onNodeWithContentDescription(app.getString(R.string.camera_preview_tools)).performClick()
        compose.onNodeWithText(app.getString(R.string.plate_solve)).performClick()
        assertGradedDatabaseChoices()
        systemBack()
        workspace("CAPTURE").assertIsSelected()
        compose.onNodeWithContentDescription(app.getString(R.string.camera_preview_tools)).assertIsDisplayed()
    }

    private fun assertGradedDatabaseChoices() {
        compose.onNodeWithText(app.getString(R.string.astap_database)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("solve-database-D20").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("solve-database-D50").performScrollTo().assertIsDisplayed()
    }

    @Test fun restoredToolsHubOpensSharedTasksAndReturnsToTools() {
        selectWorkspace("TOOLS")
        compose.onNodeWithContentDescription(app.getString(R.string.back)).assertDoesNotExist()
        captureWorkflowEvidence("tools-hub")
        compose.onNodeWithTag("tool-plate_solve").performScrollTo().performClick()
        assertGradedDatabaseChoices()
        systemBack()
        workspace("TOOLS").assertIsSelected()
        compose.onNodeWithTag("tool-post_stack").performScrollTo().performClick()
        compose.onNodeWithTag("stack-target-DEEP_SKY").performClick().assertIsSelected()
        compose.runOnIdle { org.junit.Assert.assertEquals(StackTarget.DEEP_SKY, vm.stacking.offlineTarget.value) }
        systemBack()
        compose.onNodeWithTag("tool-live_stack").performScrollTo().performClick()
        compose.onNodeWithTag("stack-target-PLANETARY").performClick().assertIsSelected()
        systemBack()
        compose.onNodeWithTag("tool-polar").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.three_point_polar_alignment)).assertIsDisplayed()
        systemBack()
        compose.onNodeWithTag("tool-player").performScrollTo().performClick()
        systemBack()
        workspace("TOOLS").assertIsSelected()
        compose.onNodeWithTag("tool-guide").performScrollTo().assertIsDisplayed()
        if (!com.indigo.mobileobservatory.sequence.SequenceFeature.ENABLED) {
            compose.onNodeWithTag("tool-sequence").assertDoesNotExist()
        }
        restoration.emulateSavedInstanceStateRestore()
        workspace("TOOLS").assertIsSelected()
        systemBack()
        workspace("CAPTURE").assertIsSelected()
    }

    private fun captureWorkflowEvidence(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(app.getExternalFilesDir(null), "workflow-ui-evidence/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun isPointingPreference(key: String) = key.startsWith("star_map_") ||
        key.startsWith("pointing_") || key == StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM

    /** Only optical choices are seeded; hardware state remains that of the real emulator. */
    private fun prepareUnconfirmedPhoneEyepiece() {
        val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        savedPointingPreferences = prefs.all.filterKeys(::isPointingPreference)
        val current = PointingOpticsConfiguration.load(AndroidOpticsPrefs(prefs))
        val snapshot = StarMapOpticsPrefs.snapshot(OpticsTrainConfig(OpticsTrainId.PRIMARY, FovInstrumentMode.EYEPIECE),
            current.secondary, StarMapOpticsPrefs.loadActive(AndroidOpticsPrefs(prefs)),
            prefs.getBoolean(StarMapOpticsPrefs.SHOW_OVERLAY, true))
        val edit = prefs.edit()
        snapshot.strings.forEach { (key, value) -> edit.putString(key, value) }
        snapshot.bools.forEach { (key, value) -> edit.putBoolean(key, value) }
        edit.remove(UserOpticsCatalog.TELESCOPES_KEY).remove(UserOpticsCatalog.EYEPIECES_KEY)
            .putBoolean(PointingOpticsConfiguration.confirmationKey(OpticsTrainId.PRIMARY), false)
            .putString("pointing_camera_source", "PHONE").commit()
        compose.waitForIdle()
    }

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
