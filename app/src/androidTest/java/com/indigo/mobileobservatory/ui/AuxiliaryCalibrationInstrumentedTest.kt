package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.screens.AuxiliaryCalibrationScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Product navigation smoke only: no fake plate is accepted or persisted by these tests. */
@RunWith(AndroidJUnit4::class)
class AuxiliaryCalibrationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: CameraViewModel
    private lateinit var restoration: StateRestorationTester
    private var installationPreferences: Map<String, String?> = emptyMap()
    private var exits = 0
    @Before fun showCameraAlignment() {
        val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        installationPreferences = listOf("pointing_installation_phone", "pointing_installation_secondary")
            .associateWith { prefs.getString(it, null) }
        val clear = prefs.edit()
        installationPreferences.keys.forEach(clear::remove)
        clear.commit()
        store = ViewModelStore()
        compose.runOnUiThread { vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java] }
        restoration = StateRestorationTester(compose)
        restoration.setContent { MobileObservatoryTheme { AuxiliaryCalibrationScreen(vm, { exits++ }) } }
    }
    @After fun close() {
        compose.runOnUiThread { store.clear() }
        val restore = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit()
        installationPreferences.forEach { (key, value) ->
            if (value == null) restore.remove(key) else restore.putString(key, value)
        }
        restore.commit()
    }
    @Test fun auxiliarySetupNeedsNoPhoneLensAndGatesUnconnectedSampling() {
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).assertExists()
        compose.onNodeWithText(app.getString(R.string.wfp_eyepiece)).assertExists()
        compose.onNodeWithText(app.getString(R.string.wfp_main_camera)).performClick()
        compose.onNodeWithTag("pointing-begin").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.wfp_select_lens)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.wfp_auto_sampling)).assertDoesNotExist()
    }
    @Test fun advancedAcquisitionOptionsExpandWithoutNestedScrollCrash() {
        compose.onNodeWithText(app.getString(R.string.wfa_raw_format)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.wfp_tracking)).assertDoesNotExist()
        compose.onNodeWithTag("pointing-options").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.wfa_raw_format)).performScrollTo().assertExists()
        compose.onNodeWithText(app.getString(R.string.wfp_tracking)).performScrollTo().assertExists()
        compose.onNodeWithTag("pointing-begin").performScrollTo().assertIsNotEnabled()
        systemBack()
        compose.onNodeWithText(app.getString(R.string.wfa_raw_format)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, exits) }
        systemBack()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun sourcePickerOffersPhoneLensesAndAuxiliaryInOneCalibrationPage() {
        compose.onNodeWithTag("pointing-source").performClick()
        compose.onNodeWithText(app.getString(R.string.wfc_phone)).assertExists()
        compose.onNodeWithTag("pointing-source-auxiliary").assertExists()
        choosePhoneSource()
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).assertExists()
        compose.onNodeWithTag("pointing-options").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.wfp_mirror)).performScrollTo().assertExists()
        compose.onNodeWithText(app.getString(R.string.wfa_raw_format)).assertDoesNotExist()
        compose.onNodeWithTag("pointing-source").performScrollTo().performClick()
        compose.onNodeWithTag("pointing-source-auxiliary").performClick()
        compose.onNodeWithText(app.getString(R.string.wf_pointing_setup)).assertExists()
        compose.onNodeWithText(app.getString(R.string.wfa_raw_format)).performScrollTo().assertExists()
        compose.onNodeWithText(app.getString(R.string.wfp_mirror)).assertDoesNotExist()
    }

    @Test fun adjustableInstallationProvidesOptionalAdjustmentAndRequiresANewLockConfirmation() {
        compose.onNodeWithTag("pointing-installation-FIXED").assertIsSelected()
        compose.onNodeWithTag("pointing-locked").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("pointing-adjust").assertDoesNotExist()
        compose.onNodeWithTag("pointing-installation-ADJUSTABLE").performClick().assertIsSelected()
        compose.onNodeWithTag("pointing-locked").assertIsOff()
        compose.onNodeWithTag("pointing-adjust").performScrollTo().assertExists().assertIsNotEnabled()
        compose.onNodeWithTag("pointing-begin").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("pointing-locked").performScrollTo().performClick().assertIsOn()
        // Connected camera, optics, location and offline index are still required after locking.
        compose.onNodeWithTag("pointing-begin").performScrollTo().assertIsNotEnabled()
    }

    @Test fun unfixableInstallationCannotBeSavedAndHidesAlignmentAndLockControls() {
        compose.onNodeWithTag("pointing-locked").performClick().assertIsOn()
        compose.onNodeWithTag("pointing-installation-UNFIXED").performClick().assertIsSelected()
        compose.onNodeWithTag("pointing-locked").assertDoesNotExist()
        compose.onNodeWithTag("pointing-adjust").assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.wfc_unfixed_hint)).assertExists()
        compose.onNodeWithTag("pointing-begin").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("pointing-installation-FIXED").performScrollTo().performClick()
        compose.onNodeWithTag("pointing-locked").assertIsOff()
    }

    @Test fun sourceChangesDiscardTheOtherCamerasLockConfirmation() {
        compose.onNodeWithTag("pointing-locked").performClick().assertIsOn()
        compose.onNodeWithTag("pointing-source").performClick()
        choosePhoneSource()
        compose.onNodeWithTag("pointing-locked").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("pointing-source").performClick()
        compose.onNodeWithTag("pointing-source-auxiliary").performClick()
        compose.onNodeWithTag("pointing-locked").assertIsOff()
        compose.onNodeWithTag("pointing-begin").performScrollTo().assertIsNotEnabled()
    }

    @Test fun sourceDialogBackReturnsToSetupWithoutLeavingTheTask() {
        compose.onNodeWithTag("pointing-source").performClick()
        compose.onNodeWithTag("pointing-source-auxiliary").assertExists()
        compose.onNodeWithText(app.getString(R.string.back)).performClick()
        compose.onNodeWithTag("pointing-source-auxiliary").assertDoesNotExist()
        compose.onNodeWithTag("pointing-source").assertExists()
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun installationAndAdvancedStateRestoreWithoutInventingObservations() {
        compose.onNodeWithTag("pointing-installation-ADJUSTABLE").performClick()
        compose.onNodeWithTag("pointing-locked").performClick()
        compose.onNodeWithTag("pointing-options").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("pointing-installation-ADJUSTABLE").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("pointing-locked").performScrollTo().assertIsOn()
        compose.onNodeWithText(app.getString(R.string.wfa_raw_format)).performScrollTo().assertExists()
        compose.onNodeWithTag("pointing-save").assertDoesNotExist()
        compose.onNodeWithTag("pointing-begin").performScrollTo().assertIsNotEnabled()
    }

    private fun choosePhoneSource() {
        val physicalLens = SemanticsMatcher("physical phone lens choice") {
            it.config.contains(SemanticsProperties.TestTag) &&
                it.config[SemanticsProperties.TestTag].startsWith("pointing-lens-")
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(physicalLens).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText(app.getString(R.string.wfp_select_lens)).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodes(physicalLens).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodes(physicalLens)[0].performScrollTo().performClick()
        } else compose.onNodeWithText(app.getString(R.string.wfp_select_lens)).performClick()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
