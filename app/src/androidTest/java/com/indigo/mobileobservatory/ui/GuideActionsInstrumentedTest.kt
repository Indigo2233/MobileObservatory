package com.indigo.mobileobservatory.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.SimulatedCamera
import com.indigo.mobileobservatory.ui.screens.GuideScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic preview fixture verifies button effects; it provides no hardware calibration evidence. */
class GuideActionsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: CameraViewModel

    @Before fun connectPreview() {
        store = ViewModelStore()
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
            val camera = SimulatedCamera("Guide action fixture").apply { setPixelFormat(PixelFormat.MONO8) }
            val manager = vm.guideCameraManager
            manager.javaClass.getDeclaredField("activeCamera").apply { isAccessible = true }.set(manager, camera)
            @Suppress("UNCHECKED_CAST")
            val connection = manager.javaClass.getDeclaredField("_connectionState").apply { isAccessible = true }
                .get(manager) as MutableStateFlow<ConnectionState>
            connection.value = ConnectionState.Connected(requireNotNull(camera.cameraInfo))
        }
        compose.setContent { MobileObservatoryTheme {
            Surface(Modifier.fillMaxSize()) { GuideScreen(vm, onBack = {}) }
        } }
        compose.waitUntil(15_000) { vm.guideDisplayFrame.value?.stars?.size?.let { it >= 2 } == true }
    }

    @After fun close() { instrumentation.runOnMainSync { store.clear() } }

    @Test fun autoSelectionOnlyLocksAStarAndLeavesStoppedExposuresStopped() {
        compose.onNodeWithTag("guide-exposure").performClick()
        compose.waitUntil(5_000) { !vm.guideExposing.value && !vm.guideExposureRequested.value }
        compose.onNodeWithTag("guide-auto-select").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertNotNull(vm.guideReferenceStar.value)
            assertFalse(vm.guideExposing.value)
            assertFalse(vm.guideExposureRequested.value)
            assertFalse(vm.guideRunning.value)
            assertFalse(vm.guideCalibrating.value)
            assertFalse(vm.guideAutoStarting.value)
        }
        compose.onNodeWithTag("guide-start-stop").assertIsNotEnabled()
    }

    @Test fun startingRequiresAnExplicitSelectionAndKeepsTheChosenStar() {
        compose.runOnIdle {
            assertNotNull(vm.guideStar.value)
            assertNull(vm.guideReferenceStar.value)
            vm.startGuiding()
            assertNull(vm.guideReferenceStar.value)
            assertEquals(app.getString(R.string.gw_select_star_first), vm.guideStatus.value)
        }
        compose.onNodeWithTag("guide-start-stop").assertIsNotEnabled()
        val fainter = requireNotNull(vm.guideDisplayFrame.value).stars.last()
        compose.runOnIdle { vm.selectGuideStar(fainter.x, fainter.y) }
        val selected = requireNotNull(vm.guideReferenceStar.value)
        compose.onNodeWithTag("guide-start-stop").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(selected, vm.guideReferenceStar.value)
            assertEquals(app.getString(R.string.connect_mount_before_guiding), vm.guideStatus.value)
            assertTrue(vm.guideExposing.value)
            assertFalse(vm.guideRunning.value)
            assertFalse(vm.guideCalibrating.value)
            assertFalse(vm.guideAutoStarting.value)
        }
    }
}
