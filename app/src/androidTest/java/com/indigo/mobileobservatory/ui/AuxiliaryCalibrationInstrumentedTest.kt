package com.indigo.mobileobservatory.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.screens.AuxiliaryCalibrationScreen
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.After
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
    @Before fun showCameraAlignment() {
        store = ViewModelStore()
        compose.runOnUiThread { vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java] }
        compose.setContent { MaterialTheme { AuxiliaryCalibrationScreen(vm, {}) } }
    }
    @After fun close() { compose.runOnUiThread { store.clear() } }
    @Test fun auxiliarySetupNeedsNoPhoneLensAndGatesUnconnectedSampling() {
        compose.onNodeWithText(app.getString(R.string.wfa_title)).assertExists()
        compose.onNodeWithText(app.getString(R.string.wfp_eyepiece)).assertExists()
        compose.onNodeWithText(app.getString(R.string.wfp_main_camera)).performClick()
        compose.onNodeWithText(app.getString(R.string.wfa_continue)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.wfp_select_lens)).assertDoesNotExist()
    }
    @Test fun advancedAcquisitionOptionsExpandWithoutNestedScrollCrash() {
        compose.onNodeWithText(app.getString(R.string.wfp_other_options)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.wfa_raw_format)).performScrollTo().assertExists()
        compose.onNodeWithText(app.getString(R.string.wfa_continue)).performScrollTo().assertIsNotEnabled()
    }
}
