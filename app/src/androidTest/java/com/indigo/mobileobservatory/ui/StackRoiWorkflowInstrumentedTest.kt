package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.ui.screens.CameraScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StackRoiWorkflowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()

    @Test fun captureAndLiveStackSharePlannerAndApplyThroughTheCameraBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        val autoCheck = prefs.getBoolean("update_auto_check", true)
        prefs.edit().putBoolean("update_auto_check", false).commit()
        val store = ViewModelStore()
        lateinit var vm: CameraViewModel
        try {
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
                val delegate = SimulatedCamera("ROI UI fixture")
                // A raw-layout UI fixture with no capture-generation proof: useful for ROI
                // control, never used as evidence of a real camera or scientific calibration.
                val camera = object : Camera by delegate {
                    override val recordsLiveViewAsScience = true
                    override val sciencePixelTraits get() = CameraScienceLayouts.fullContainer(currentPixelFormat)
                }
                // Publish the adapter BEFORE Connected. Replacing a running demo camera can
                // race its first collector and correctly discard all frames as stale-source.
                vm.cameraManager.javaClass.getDeclaredField("activeCamera").apply { isAccessible = true }.set(vm.cameraManager, camera)
                @Suppress("UNCHECKED_CAST")
                val state = vm.cameraManager.javaClass.getDeclaredField("_connectionState").apply { isAccessible = true }
                    .get(vm.cameraManager) as MutableStateFlow<ConnectionState>
                state.value = ConnectionState.Connected(requireNotNull(camera.cameraInfo))
            }
            compose.setContent { MobileObservatoryTheme { Surface(Modifier.fillMaxSize()) { CameraScreen(vm) } } }
            compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            try {
                compose.waitUntil(10_000) { vm.previewBitmap.value != null && vm.stackRoiContext()?.let { it.blockedReason == null } == true }
            } catch (failure: Throwable) {
                throw AssertionError("ROI fixture: preview=${vm.previewBitmap.value != null}, " +
                    "camera=${vm.cameraManager.activeCamera?.currentPixelFormat}, " +
                    "capturing=${vm.cameraManager.activeCamera?.isCapturing?.value}, " +
                    "context=${vm.stackRoiContext()}, calibration=${vm.stacking.calibration.state.value}", failure)
            }
            compose.onNodeWithTag("capture-settings-open").performClick()
            compose.onNode(hasText(app.getString(R.string.section_roi)) and hasClickAction()).performClick()
            compose.onNodeWithTag("stack-roi-planner").assertExists()
            compose.onNodeWithTag("stack-roi-aspect-SQUARE").performScrollTo().performClick()
            compose.onNodeWithTag("stack-roi-apply").performScrollTo().performClick()
            compose.waitUntil(5_000) { vm.roi.value.width == vm.roi.value.height && vm.roi.value.width > 0 }
            assertTrue(vm.roi.value.width <= 480)
            compose.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
            val previous = requireNotNull(vm.stackRoiContext())
            compose.runOnIdle { vm.setBinning(2) }
            compose.waitUntil(5_000) { vm.binning.value == 2 && vm.stackRoiContext()?.blockedReason == null }
            compose.runOnIdle {
                assertFalse(vm.applyStackRoi(previous, previous.key.roi))
                assertEquals(previous.key.roi, vm.roi.value)
            }
            compose.onNodeWithContentDescription(app.getString(R.string.camera_preview_tools)).performClick()
            compose.onNodeWithText(app.getString(R.string.stack_live)).performClick()
            compose.onNodeWithTag("stack-roi-open").performScrollTo().performClick()
            compose.onNodeWithTag("stack-roi-planner").assertExists()
            compose.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
            compose.onNodeWithTag("stack-roi-open").assertExists()
            assertFalse(compose.activity.isFinishing)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            prefs.edit().putBoolean("update_auto_check", autoCheck).commit()
        }
    }

    @Test fun recordingWaitsForAnInFlightRoiTransaction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        val autoCheck = prefs.getBoolean("update_auto_check", true)
        prefs.edit().putBoolean("update_auto_check", false).commit()
        val store = ViewModelStore()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var vm: CameraViewModel? = null
        val delegate = SimulatedCamera("ROI recording gate fixture")
        val camera = object : Camera by delegate {
            override fun setRoi(roi: Roi) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "ROI fixture timed out" }
                delegate.setRoi(roi)
            }
        }
        try {
            instrumentation.runOnMainSync {
                val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
                vm = model
                model.cameraManager.javaClass.getDeclaredField("activeCamera").apply { isAccessible = true }
                    .set(model.cameraManager, camera)
                model.setRoi(Roi(8, 8, 320, 240))
            }
            assertTrue("ROI write never reached the camera boundary", entered.await(5, TimeUnit.SECONDS))
            val model = requireNotNull(vm)
            instrumentation.runOnMainSync { model.startRecording() }
            assertFalse(model.isRecording.value)
            assertEquals(app.getString(R.string.camera_cfa_busy_settings), model.statusMessage.value)
        } finally {
            release.countDown()
            vm?.let { model -> instrumentation.runOnMainSync {
                model.stopRecording()
                store.clear()
            } }
            prefs.edit().putBoolean("update_auto_check", autoCheck).commit()
        }
    }
}
