package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.mount.MountAccessMode
import com.indigo.mobileobservatory.mount.MountPointingAccess
import com.indigo.mobileobservatory.pointing.PointingMountCapabilities
import com.indigo.mobileobservatory.ui.screens.MountControlScreen
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MountAccessUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: CameraViewModel
    private lateinit var access: MountPointingAccess
    private var originalMode: String? = null

    @Before fun showActualMountControlsWithKnownCapabilities() {
        originalMode = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).getString("mount_access_mode", null)
        store = ViewModelStore()
        compose.runOnUiThread {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
            val module = vm.javaClass.getDeclaredField("mountModule").apply { isAccessible = true }.get(vm)
            val controller = module.javaClass.getDeclaredField("controller").apply { isAccessible = true }.get(module)
            access = controller.javaClass.getDeclaredField("pointingAccess").apply { isAccessible = true }.get(controller) as MountPointingAccess
            access.setAccessMode(MountAccessMode.READ_WRITE)
            access.connected(PointingMountCapabilities(readCoordinates = true, goto = true, sync = true, tracking = true,
                manualMove = true, home = true, setHome = true, readSite = true, writeSite = true, readTime = true, writeTime = true), "TEST")
            @Suppress("UNCHECKED_CAST")
            val connection = module.javaClass.getDeclaredField("_mountConnectionState").apply { isAccessible = true }.get(module) as MutableStateFlow<MountConnectionState>
            connection.value = MountConnectionState.Connected
        }
        compose.setContent { MaterialTheme { MountControlScreen(vm, {}, {}, {}, showObservingShortcuts = false) } }
    }
    @After fun closeControls() {
        compose.runOnUiThread { store.clear() }
        val edit = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit()
        originalMode?.let { edit.putString("mount_access_mode", it) } ?: edit.remove("mount_access_mode")
        edit.commit()
    }

    @Test fun missingImagingCameraProducesVisiblePrecisionTaskFailureBeforeMotion() {
        compose.runOnIdle {
            org.junit.Assert.assertFalse(vm.startPrecisionGoto("M42", 5.5, -5.0, "J2000"))
            val progress = vm.precisionGotoProgress.value
            org.junit.Assert.assertEquals(com.indigo.mobileobservatory.mount.PrecisionGotoPhase.FAILED, progress.phase)
            org.junit.Assert.assertEquals("M42", progress.targetName)
            org.junit.Assert.assertEquals(text(R.string.precision_goto_need_camera), progress.message)
            org.junit.Assert.assertFalse(progress.isActive)
            org.junit.Assert.assertFalse(vm.mountMotionState.value.isActive)
        }
    }

    @Test fun readonlyDisablesMotionAndWritesButKeepsReadsAndStop() {
        compose.onNodeWithTag("mount-read-only").performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.move_north)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.tracking_sidereal)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.home)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.set_home)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.stop_mount)).assertIsEnabled()
        openSiteAndTime()
        compose.onNodeWithText(text(R.string.mount_to_app)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.phone_to_mount)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mount_time_to_app)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.phone_time_to_mount)).assertIsNotEnabled()
    }

    @Test fun protocolReadCapabilityDoesNotGrantUnsupportedHomeOrTimeWrites() {
        compose.runOnIdle {
            access.connected(PointingMountCapabilities(readCoordinates = true, readSite = true, readTime = true), "READ_ONLY_PROTOCOL")
        }
        compose.onNodeWithText(text(R.string.home)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.set_home)).assertIsNotEnabled()
        openSiteAndTime()
        compose.onNodeWithText(text(R.string.mount_to_app)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.mount_time_to_app)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.phone_time_to_mount)).assertIsNotEnabled()
    }

    @Test fun directMotorLocalSiteIsExplicitAndAvailableWithoutControllerWrites() {
        compose.runOnIdle {
            access.connected(PointingMountCapabilities(readCoordinates = true, readSite = true,
                siteUsesLocalConfiguration = true), "DIRECT_MOTOR")
            access.setAccessMode(MountAccessMode.READ_ONLY)
        }
        openSiteAndTime()
        compose.onNodeWithText(text(R.string.wf_mount_read_app_site)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.wf_mount_phone_to_app_site)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.phone_to_mount)).assertDoesNotExist()
    }

    private fun openSiteAndTime() {
        if (compose.onAllNodesWithText(text(R.string.wf_mount_site_time)).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithText(text(R.string.section_device)).performScrollTo().performClick()
        }
        compose.onNodeWithText(text(R.string.wf_mount_site_time)).performScrollTo().performClick()
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
