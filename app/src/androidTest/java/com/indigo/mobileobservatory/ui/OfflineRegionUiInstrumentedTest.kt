package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.stacking.*
import com.indigo.mobileobservatory.ui.screens.StackingScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineRegionUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()

    @Test fun oversizedVideoShowsPreviewAndOffersWorkingBinAndCropBeforeAnalysis() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        val autoCheck = prefs.getBoolean("update_auto_check", true)
        prefs.edit().putBoolean("update_auto_check", false).commit()
        val dir = File(app.cacheDir, "offline-roi-ui-${System.nanoTime()}").apply { mkdirs() }
        val file = OfflineRegionInstrumentedTest.recording(dir, false)
        val store = ViewModelStore()
        lateinit var vm: CameraViewModel
        try {
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
                compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                vm.stacking.open(StackMode.PLANETARY)
            }
            compose.setContent { MobileObservatoryTheme { Surface(Modifier.fillMaxSize()) {
                StackingScreen(vm, StackMode.PLANETARY, onBack = {})
            } } }
            compose.waitUntil(5000) { vm.stacking.calibration.state.value.initialized }
            compose.runOnIdle { vm.stacking.loadFile(file) }
            compose.waitUntil(15_000) { !vm.stacking.hasPlanetaryWork() && vm.stacking.task.value.phase == StackTaskPhase.READY }
            assertNotNull(vm.stacking.sourcePreview.value)
            compose.onNodeWithTag("offline-analyze").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("offline-budget").performScrollTo().assertIsDisplayed()
            evidence(app, "full-size-budget")
            compose.onNodeWithTag("offline-bin-suggested").performScrollTo().performClick()
            compose.onNodeWithTag("offline-analyze").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("offline-budget").performScrollTo().assertTextContains("964×545", substring = true)
            evidence(app, "full-field-bin4")
            compose.onNodeWithTag("offline-bin-1").performScrollTo().performClick()
            compose.onNodeWithTag("offline-roi-suggested").performScrollTo().performClick()
            compose.onNodeWithTag("offline-analyze").performScrollTo().assertIsEnabled()
            compose.onNodeWithText(app.getString(R.string.stack_draw_roi)).performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.stack_offline_drag_hint)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.stack_drag_hint)).assertDoesNotExist()
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            dir.deleteRecursively()
            prefs.edit().putBoolean("update_auto_check", autoCheck).commit()
        }
    }
    private fun evidence(app: Application, name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(app.getExternalFilesDir(null), "offline-region-ui/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
