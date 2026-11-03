package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.camera.SimulatedCamera
import com.indigo.mobileobservatory.stacking.StackMode
import com.indigo.mobileobservatory.ui.screens.StackingScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stacked picture opens a full-screen viewer. The system back button closes
 * the viewer and leaves the stacking page open.
 */
@RunWith(AndroidJUnit4::class)
class StackingFullscreenViewerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()

    @Test fun theStackedPictureOpensTheFullScreenViewerAndTheBackButtonClosesIt() {
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
                val camera = SimulatedCamera("Fullscreen viewer fixture")
                vm.cameraManager.javaClass.getDeclaredField("activeCamera").apply { isAccessible = true }
                    .set(vm.cameraManager, camera)
                @Suppress("UNCHECKED_CAST")
                val state = vm.cameraManager.javaClass.getDeclaredField("_connectionState").apply { isAccessible = true }
                    .get(vm.cameraManager) as MutableStateFlow<ConnectionState>
                state.value = ConnectionState.Connected(requireNotNull(camera.cameraInfo))
            }
            compose.setContent { MobileObservatoryTheme {
                Surface(Modifier.fillMaxSize()) {
                    StackingScreen(vm, StackMode.LIVE, onBack = { })
                }
            } }
            // The picture has to be on screen before it can be opened.
            compose.waitUntil(10_000) { vm.previewBitmap.value != null }
            compose.onNodeWithTag("stack-sample-surface").assertIsDisplayed()

            // The magnify control opens the full-screen viewer.
            compose.onNodeWithTag("stack-fullscreen-open").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("fullscreen-image-viewer").assertIsDisplayed()
            compose.onNodeWithTag("fullscreen-image-surface").assertIsDisplayed()

            // The system back button leaves the viewer and keeps the page open.
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.onNodeWithTag("fullscreen-image-viewer").assertDoesNotExist()
            compose.onNodeWithTag("stack-sample-surface").assertIsDisplayed()
            assertFalse(compose.activity.isFinishing)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            prefs.edit().putBoolean("update_auto_check", autoCheck).commit()
        }
    }
}
