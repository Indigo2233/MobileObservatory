package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.width
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.camera.SimulatedCamera
import com.indigo.mobileobservatory.ui.components.RecordLimit
import com.indigo.mobileobservatory.ui.components.RecordLimitType
import com.indigo.mobileobservatory.ui.screens.CameraScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real preview producer, processing pipeline and shell; no injected connected-camera state. */
@RunWith(AndroidJUnit4::class)
class CapturePortraitInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: CameraViewModel
    private var originalPrefs: Map<String, *> = emptyMap<String, Any>()
    private val fontScale = mutableStateOf(1f)
    private val night = mutableStateOf(false)

    @Before fun startShell() {
        val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        originalPrefs = prefs.all
        prefs.edit().putBoolean("update_auto_check", false).commit()
        store = ViewModelStore()
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                MobileObservatoryTheme(redNightMode = night.value) {
                    Surface(Modifier.fillMaxSize()) {
                        CameraScreen(vm, redNightMode = night.value, onRedNightModeChange = { night.value = it })
                    }
                }
            }
        }
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
    }

    @After fun closeShell() {
        shell("settings put system font_scale 1.0")
        instrumentation.runOnMainSync { store.clear() }
        val edit = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit().clear()
        originalPrefs.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Float -> edit.putFloat(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        edit.commit()
    }

    @Test fun portraitFillsWidthAndSettingsNeverResizeOrReplaceThePreview() {
        startDemo()
        val width = compose.onNodeWithTag("capture-preview").getUnclippedBoundsInRoot().width
        assertEquals(compose.onRoot().getUnclippedBoundsInRoot().width.value, width.value, 1f)
        evidence("portrait")
        compose.onNodeWithTag("capture-settings-open").performClick()
        compose.onNodeWithTag("capture-settings-sheet").assertIsDisplayed()
        assertEquals(width.value, compose.onNodeWithTag("capture-preview").getUnclippedBoundsInRoot().width.value, 1f)
        evidence("portrait-settings")
        compose.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        compose.onNodeWithTag("capture-settings-sheet").assertDoesNotExist()
        compose.onNodeWithTag("capture-exposure").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("2s")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5_000) { vm.exposureUs.value == 2_000_000f }
        evidence("portrait-exposure")
        compose.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        compose.onNodeWithTag("capture-gain").performClick()
        compose.onNodeWithTag("capture-parameter-sheet").assertIsDisplayed()
        systemBack()
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("capture-parameter-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("capture-parameter-sheet").assertDoesNotExist()
        compose.onNodeWithTag("workspace-CAPTURE").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun bothParameterSheetsCanStopRecordingWithoutSavingOrQueuingTestFrames() {
        val filesBefore = mediaFiles()
        startDemo()
        listOf("capture-exposure", "capture-settings-open").forEach { tag ->
            compose.onNodeWithContentDescription(app.getString(R.string.start_recording)).performClick()
            compose.waitUntil(5_000) { vm.recordingFrameCount.value >= 3 }
            compose.onNodeWithTag(tag).performClick()
            compose.onNodeWithText(app.getString(R.string.stop_recording)).assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { !vm.isRecording.value }
            compose.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        }
        compose.onNodeWithContentDescription(app.getString(R.string.capture_desc)).performClick()
        compose.runOnIdle {
            assertEquals(0L, vm.recordingBytes.value)
            assertFalse(vm.scienceCaptureReady.value)
            assertNull(vm.cameraManager.activeCamera!!.scienceCaptureGeneration)
            val queue = vm.javaClass.getDeclaredField("recordWriteQueue").apply { isAccessible = true }.get(vm) as java.util.Queue<*>
            assertTrue("Demo frames must not enter the file-writer queue", queue.isEmpty())
        }
        assertEquals(filesBefore, mediaFiles())
    }

    @Test fun rotationKeepsCameraAndExposureAndLargeTextKeepsCloseAndStopReachable() {
        startDemo()
        val camera = vm.cameraManager.activeCamera
        compose.runOnIdle { vm.setExposure(500_000f) }
        compose.waitUntil(5_000) { vm.exposureUs.value == 500_000f }
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        compose.onNodeWithTag("capture-settings-open").assertIsNotDisplayed()
        evidence("landscape")
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        compose.onNodeWithTag("capture-settings-open").assertIsDisplayed()
        assertSame(camera, vm.cameraManager.activeCamera)
        assertEquals(500_000f, vm.exposureUs.value)
        // A dialog has its own LocalDensity: change the system scale to cover its real window too.
        shell("settings put system font_scale 2.0")
        compose.waitUntil(5_000) { compose.activity.resources.configuration.fontScale >= 1.9f }
        compose.runOnIdle { fontScale.value = 2f; night.value = true }
        compose.onNodeWithContentDescription(app.getString(R.string.start_recording)).performClick()
        compose.onNodeWithTag("capture-settings-open").performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.stop_recording)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.close)).assertIsDisplayed()
        evidence("portrait-large-text-night")
        compose.onNodeWithText(app.getString(R.string.stop_recording)).performClick()
        systemBack()
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("capture-settings-sheet").fetchSemanticsNodes().isEmpty() }
        assertFalse(vm.isRecording.value)
    }

    @Test fun formatAndLimitMenusReturnToCaptureWithSystemBack() {
        startDemo()
        listOf(R.string.capture_photo_label to "JPG", R.string.capture_video_label to vm.recordFormat.value.name).forEach { (label, format) ->
            compose.onNodeWithText("${app.getString(label)} $format").performClick()
            compose.onNodeWithText("FITS").assertIsDisplayed()
            systemBack()
            compose.waitUntil(3_000) { compose.onAllNodesWithText("FITS").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("workspace-CAPTURE").assertIsSelected()
        }
        compose.onNodeWithText(app.getString(R.string.limit_label)).performClick()
        compose.onNodeWithText(app.getString(R.string.no_limit)).assertIsDisplayed()
        systemBack()
        compose.waitUntil(3_000) { compose.onAllNodesWithText(app.getString(R.string.no_limit)).fetchSemanticsNodes().isEmpty() }
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun simulatorLimitsAndExitCleanUpAndNoDuplicateNightToggleAppears() {
        startDemo()
        assertFalse(vm.cameraManager.openSimulatedCamera("Another demo"))
        assertFalse(vm.guideCameraManager.openSimulatedCamera("Guide demo"))
        compose.runOnIdle { vm.setRecordLimit(RecordLimit(RecordLimitType.FRAMES, 4)); vm.startRecording() }
        compose.waitUntil(5_000) { !vm.isRecording.value }
        assertTrue(vm.recordingFrameCount.value >= 4)
        compose.runOnIdle { vm.setRecordLimit(RecordLimit(RecordLimitType.TIME, 1)); vm.startRecording() }
        compose.waitUntil(5_000) { !vm.isRecording.value }
        assertTrue(vm.recordingDurationMs.value >= 1_000)
        compose.onNodeWithContentDescription(app.getString(R.string.camera_preview_tools)).performClick()
        compose.onNodeWithText(app.getString(R.string.red_night_mode)).assertDoesNotExist()
        systemBack()
        compose.waitUntil(3_000) { compose.onAllNodesWithText(app.getString(R.string.sim_camera_stop)).fetchSemanticsNodes().size == 1 }
        compose.runOnIdle { vm.setRecordLimit(RecordLimit(RecordLimitType.NONE, 0)); vm.startRecording() }
        val camera = vm.cameraManager.activeCamera as SimulatedCamera
        compose.onNodeWithText(app.getString(R.string.sim_camera_stop)).performClick()
        compose.waitUntil(5_000) { vm.connectionState.value is ConnectionState.Disconnected && !vm.isRecording.value }
        assertFalse(camera.isOpen.value)
        assertNull(vm.previewBitmap.value)
        compose.onNodeWithText(app.getString(R.string.sim_camera_start)).assertIsDisplayed()
    }

    private fun startDemo() {
        compose.onNodeWithText(app.getString(R.string.sim_camera_start)).performClick()
        compose.waitUntil(10_000) { vm.previewBitmap.value != null && (vm.cameraManager.activeCamera as? SimulatedCamera)?.framesEmitted?.let { it >= 4 } == true }
        compose.onNodeWithText(app.getString(R.string.sim_camera_badge)).assertIsDisplayed()
    }

    private fun rotate(request: Int, expected: Int) {
        compose.runOnIdle { compose.activity.requestedOrientation = request }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == expected }
        compose.waitForIdle()
    }

    private fun systemBack() {
        // Sheets and menus own a dialog window; exercise platform Back through that window.
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        shell("input keyevent 4")
        compose.waitForIdle()
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun mediaFiles() = listOf("captures", "recordings").flatMap { dir ->
        app.getExternalFilesDir(dir)?.walkTopDown()?.filter { it.isFile }?.map { it.absolutePath to it.length() }?.toList().orEmpty()
    }.toSet()

    private fun evidence(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(app.getExternalFilesDir(null), "capture-ui-evidence/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
