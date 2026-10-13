package com.indigo.mobileobservatory.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.MediaCategory
import com.indigo.mobileobservatory.recording.MediaLibrary
import com.indigo.mobileobservatory.recording.SERWriter
import com.indigo.mobileobservatory.stacking.LiveStackPhase
import com.indigo.mobileobservatory.stacking.StackMode
import com.indigo.mobileobservatory.stacking.StackTaskPhase
import com.indigo.mobileobservatory.ui.screens.StackingScreen
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Beginner entry points use the real ViewModel, app library, SER reader and Compose controls. */
@RunWith(AndroidJUnit4::class)
class StackingUsabilityInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: CameraViewModel
    private var fixture: File? = null

    @Before fun createViewModel() {
        store = ViewModelStore()
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
        }
    }

    @After fun cleanUp() {
        // Close SER/task resources before removing our uniquely named source.
        instrumentation.runOnMainSync { store.clear() }
        fixture?.delete()
    }

    @Test fun myRecordingsButtonLoadsOriginalAppSerWithoutCreatingAnImportCopy() {
        val source = createRecording().also { fixture = it }
        val beforeHash = sha256(source)
        val importsBefore = importedFiles()
        instrumentation.runOnMainSync { vm.stacking.open(StackMode.PLANETARY) }
        compose.setContent { MaterialTheme { StackingScreen(vm, StackMode.PLANETARY, onBack = {}) } }

        compose.onNodeWithText(app.getString(R.string.stack_empty)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.stack_planetary_steps)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.stack_local_files)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(source.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(source.name).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(20_000) {
            vm.stacking.task.value.phase == StackTaskPhase.READY || vm.stacking.task.value.phase == StackTaskPhase.FAILED
        }

        val state = vm.stacking.task.value
        assertEquals(state.message, StackTaskPhase.READY, state.phase)
        assertEquals(source.canonicalPath, state.source!!.file.canonicalPath)
        assertEquals(64, state.source!!.width)
        assertEquals(48, state.source!!.height)
        assertEquals(4L, state.source!!.frames)
        assertArrayEquals(beforeHash, sha256(source))
        assertEquals("Picking an app recording must reuse it instead of running the external import/copy path", importsBefore, importedFiles())
        compose.onNodeWithText(app.getString(R.string.stack_planetary_steps)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.stack_phase_ready)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.stack_analyze)).performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription(app.getString(R.string.stack_accumulated)).assertIsDisplayed()
    }

    @Test fun eaaWithoutCameraExplainsTheBlockAndNavigatesStraightToCameraSettings() {
        assertEquals("This test deliberately exercises the disconnected state", "connect_camera", vm.liveStackStartIssue())
        instrumentation.runOnMainSync { vm.stacking.open(StackMode.LIVE) }
        var cameraReached by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            if (cameraReached) Text("camera-settings-destination-reached")
            else StackingScreen(vm, StackMode.LIVE, onBack = {}, onCamera = { cameraReached = true })
        } }

        compose.onNodeWithText(app.getString(R.string.stack_eaa_intro)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.stack_reason_camera)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.stack_start)).performScrollTo().assertIsNotEnabled()
        assertEquals(LiveStackPhase.EMPTY, vm.stacking.live.state.value.phase)
        compose.onNodeWithText(app.getString(R.string.stack_camera_settings)).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("camera-settings-destination-reached").assertIsDisplayed()
    }

    @Test fun noCameraFrameShowsEmptyObservationAndCannotSaveAnImaginaryResult() {
        instrumentation.runOnMainSync { vm.stacking.open(StackMode.LIVE) }
        compose.setContent { MaterialTheme { StackingScreen(vm, StackMode.LIVE, onBack = {}) } }
        assertNull(vm.previewBitmap.value)
        assertNull(vm.stacking.preview.value)
        compose.onNodeWithText(app.getString(R.string.stack_empty)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.stack_eaa_single_hint)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.stack_export)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(app.getString(R.string.stack_accumulated)).assertDoesNotExist()
        assertFalse(vm.stacking.hasResult(StackMode.LIVE))
    }

    private fun createRecording(): File {
        val directory = File(vm.getRecordingsDir(), "SER").apply { check(isDirectory || mkdirs()) }
        val file = File(directory, "usability-${UUID.randomUUID()}.ser")
        val writer = SERWriter(file)
        writer.open(64, 48, PixelFormat.MONO8)
        try {
            repeat(4) { id ->
                val bytes = ByteArray(64 * 48) { index ->
                    val x = index % 64; val y = index / 64
                    (15 + (x * 5 + y * 7 + id) % 210).toByte()
                }
                writer.writeFrame(FrameData(bytes, 64, 48, PixelFormat.MONO8, id.toLong(), 1_000_000L + id * 100_000L))
            }
        } finally { writer.close() }
        return file
    }

    private fun importedFiles() = MediaLibrary.list(vm.getRecordingsDir(), vm.getCapturesDir(), MediaCategory.SER_PSER)
        .filter { it.name.startsWith("import-") }.map { it.file.canonicalPath }.toSet()

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
