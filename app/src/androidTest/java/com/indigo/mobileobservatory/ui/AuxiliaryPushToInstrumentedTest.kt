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
import com.indigo.mobileobservatory.pointing.SkyDirection
import com.indigo.mobileobservatory.ui.screens.AuxiliaryPushToScreen
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuxiliaryPushToInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: CameraViewModel
    private var calibrations = 0
    @Before fun showRealConsumer() {
        store = ViewModelStore()
        compose.runOnUiThread { vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java] }
        compose.setContent { MaterialTheme { AuxiliaryPushToScreen(vm, SkyDirection(83.8, -5.4), "M42", {}, { calibrations++ }) } }
    }
    @After fun close() { compose.runOnUiThread { store.clear() } }
    @Test fun noCalibrationCannotBorrowTheAuxiliaryDirectionOrTurnArrivalGreen() {
        compose.onNodeWithText(app.getString(R.string.wfa_push_calibrate)).assertExists()
        compose.onNodeWithText(app.getString(R.string.wfa_push_primary_axis)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.wf_push_verified)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.wfa_push_sample)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.wfa_title)).performScrollTo().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, calibrations) }
    }
    @Test fun USBPolicyIsDisclosedOnlyInContextualOptionsAndUsesNoPhoneLensPicker() {
        compose.onNodeWithText(app.getString(R.string.wfa_push_policy)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.wfp_other_options)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.wfa_push_policy)).performScrollTo().assertExists()
        compose.onNodeWithText(app.getString(R.string.wfp_select_lens)).assertDoesNotExist()
    }
}
