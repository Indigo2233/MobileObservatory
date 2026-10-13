package com.indigo.mobileobservatory.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.SERWriter
import com.indigo.mobileobservatory.stacking.*
import com.indigo.mobileobservatory.ui.screens.DemosaicSelector
import com.indigo.mobileobservatory.ui.screens.StackingScreen
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemosaicUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test fun threeChoicesNeedConfirmationBeforeDiscardingAResultAndRespectTheLock() {
        var selected by mutableStateOf(DemosaicAlgorithm.DDFAPD)
        var enabled by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            DemosaicSelector(selected, enabled, requiresResetConfirmation = true) { selected = it }
        } }
        compose.onNodeWithTag("stack-debayer-selector").performClick()
        DemosaicAlgorithm.values().forEach { compose.onNodeWithTag("stack-debayer-${it.name}").assertIsDisplayed() }
        compose.onNodeWithTag("stack-debayer-VNG").performClick()
        compose.onNodeWithText(app.getString(R.string.stack_debayer_reanalyze)).assertIsDisplayed()
        assertEquals(DemosaicAlgorithm.DDFAPD, selected)
        compose.onNodeWithText(app.getString(R.string.cancel)).performClick()
        assertEquals(DemosaicAlgorithm.DDFAPD, selected)
        compose.onNodeWithTag("stack-debayer-selector").performClick()
        compose.onNodeWithTag("stack-debayer-VNG").performClick()
        compose.onNode(hasText(app.getString(R.string.stack_debayer_change)) and hasClickAction()).performClick()
        assertEquals(DemosaicAlgorithm.VNG, selected)
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("stack-debayer-selector").assertIsNotEnabled()
    }

    @Test fun bayerSerShowsDefaultDdfapdAndSwitchingToVngReloadsTheActualSource() = withWorkspace(PixelFormat.BAYER_RG8) { vm ->
        compose.onNode(hasText(app.getString(R.string.stack_roi_x)) and hasSetTextAction()).performScrollTo().performTextReplacement("4")
        compose.onNode(hasText(app.getString(R.string.stack_roi_w)) and hasSetTextAction()).performScrollTo().performTextReplacement("48")
        compose.onNodeWithTag("stack-debayer-selector").performScrollTo().assertIsEnabled().performClick()
        assertEquals(DemosaicAlgorithm.DDFAPD, vm.stacking.demosaicAlgorithm.value)
        screenshot("bayer-algorithms", isPopup())
        compose.onNodeWithTag("stack-debayer-VNG").performClick()
        compose.waitUntil(15_000) { vm.stacking.demosaicAlgorithm.value == DemosaicAlgorithm.VNG && !vm.stacking.task.value.busy }
        assertTrue(vm.stacking.task.value.source!!.isBayer)
        assertEquals(StackTaskPhase.READY, vm.stacking.task.value.phase)
        compose.onNodeWithTag("stack-debayer-selector").assertTextContains(app.getString(R.string.stack_debayer_summary, "VNG"))
        screenshot("bayer-vng", isRoot())
        compose.onNode(hasText(app.getString(R.string.stack_roi_x)) and hasSetTextAction()).assertTextContains("4")
        compose.onNode(hasText(app.getString(R.string.stack_roi_w)) and hasSetTextAction()).assertTextContains("48")
    }

    @Test fun monoSerHidesBayerOnlyControls() = withWorkspace(PixelFormat.MONO8) { vm ->
        assertFalse(vm.stacking.task.value.source!!.isBayer)
        compose.onNodeWithTag("stack-debayer-selector").assertDoesNotExist()
    }

    private fun withWorkspace(format: PixelFormat, test: (CameraViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var vm: CameraViewModel
        val file = File(app.cacheDir, "debayer-ui-${UUID.randomUUID()}.ser")
        val writer = SERWriter(file)
        writer.open(64,48,format)
        try {
            repeat(3) { i -> writer.writeFrame(FrameData(ByteArray(64*48) { p -> (20+(p*17)%190).toByte() },64,48,format,i.toLong(),i*100_000L)) }
        } finally { writer.close() }
        try {
            instrumentation.runOnMainSync { vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java] }
            // Match MainActivity's Surface so screenshot text uses the actual app content color.
            compose.setContent { MobileObservatoryTheme { Surface { StackingScreen(vm, StackMode.PLANETARY, onBack = {}) } } }
            compose.waitUntil(15_000) { vm.stacking.calibration.state.value.initialized && !vm.stacking.calibration.state.value.busy }
            compose.runOnIdle { vm.stacking.open(StackMode.PLANETARY, file) }
            compose.waitUntil(15_000) { vm.stacking.task.value.source != null && !vm.stacking.task.value.busy }
            test(vm)
        } finally { instrumentation.runOnMainSync { store.clear() }; file.delete() }
    }

    private fun screenshot(name: String, node: SemanticsMatcher) {
        compose.waitForIdle()
        val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: app.getExternalFilesDir("test-artifacts"), "demosaic-ui").apply { mkdirs() }
        compose.onNode(node).captureToImage().asAndroidBitmap().let { image ->
            File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
