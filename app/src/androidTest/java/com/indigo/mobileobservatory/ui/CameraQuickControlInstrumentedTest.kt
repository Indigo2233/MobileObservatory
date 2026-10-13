package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.CameraInfo
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.camera.CoolingInfo
import com.indigo.mobileobservatory.ui.screens.CameraScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the connected-camera branch omitted by the original disconnected-device suite. */
@RunWith(AndroidJUnit4::class)
class CameraQuickControlInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: CameraViewModel
    private var originalAutoCheck: Boolean? = null
    private val fontScale = mutableStateOf(1f)

    @Before fun startConnectedCameraUi() {
        val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        originalAutoCheck = if (prefs.contains("update_auto_check")) prefs.getBoolean("update_auto_check", true) else null
        prefs.edit().putBoolean("update_auto_check", false).commit()
        store = ViewModelStore()
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
            setFlow(vm.cameraManager, "_connectionState", ConnectionState.Connected(CameraInfo(
                "AE676C(USB2.0)", "WF-000-0000", 3536, 3536, 16)))
        }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                MobileObservatoryTheme { CameraScreen(vm) }
            }
        }
        compose.runOnIdle {
            setFlow(vm, "_coolingInfo", CoolingInfo(true, true, -400, 300))
            setFlow(vm, "_sensorTempTenths", 209)
            setFlow(vm, "_targetTempTenths", -100)
            setFlow(vm, "_coolingPowerPct", 32f)
        }
    }

    @After fun closeUi() {
        instrumentation.runOnMainSync { store.clear() }
        val edit = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit()
        originalAutoCheck?.let { edit.putBoolean("update_auto_check", it) } ?: edit.remove("update_auto_check")
        edit.commit()
    }

    @Test fun connectedCameraStatusShowsCoolingAndClosesWithoutLeavingCapture() {
        openCameraStatus()
        compose.onNodeWithText("AE676C(USB2.0)").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.sn_label, "WF-000-0000")).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.wf_device_sensor_temperature, 20.9, 32.0)).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText(app.getString(R.string.target_temp_label))).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        compose.onNodeWithTag("workspace-CAPTURE").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun connectedCameraSheetIsBoundedInLandscapeWithLargeFontAndKeyboard() {
        compose.runOnIdle {
            fontScale.value = 2f
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        openCameraStatus()
        compose.onNode(hasSetTextAction() and hasText(app.getString(R.string.target_temp_label)))
            .performScrollTo().performTextReplacement("-15")
        compose.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        compose.onNodeWithTag("workspace-CAPTURE").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun connectedCoolerStatusUsesTheSameSafeCameraControlAndReturnPath() {
        compose.onNodeWithContentDescription("${app.getString(R.string.cooler)} status")
            .performScrollTo().performClick()
        compose.onNodeWithText("AE676C(USB2.0)").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.wf_device_sensor_temperature, 20.9, 32.0)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        compose.onNodeWithTag("workspace-CAPTURE").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
    }

    private fun openCameraStatus() {
        compose.onNodeWithContentDescription("${app.getString(R.string.camera)} status")
            .performScrollTo().performClick()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> setFlow(owner: Any, name: String, value: T) {
        val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        (field.get(owner) as MutableStateFlow<T>).value = value
    }
}
