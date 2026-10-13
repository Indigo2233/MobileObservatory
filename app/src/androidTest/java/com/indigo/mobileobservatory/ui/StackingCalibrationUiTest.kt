package com.indigo.mobileobservatory.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.stacking.*
import com.indigo.mobileobservatory.ui.screens.BackgroundEditor
import com.indigo.mobileobservatory.ui.screens.StackingScreen
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min
import java.io.File
import java.util.Locale
import android.content.res.Configuration

@RunWith(AndroidJUnit4::class)
class StackingCalibrationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test fun autoGcIsDefaultAndManualDbeEditsAreAppliedOnlyAfterTwelveDistributedSamples() {
        val bitmap = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(200*160) { index ->
            val x = index % 200; val y = index / 200
            val star = 210 * kotlin.math.exp(-((x-24.0)*(x-24.0)+(y-19.0)*(y-19.0))/2)
            val sky = 18 + x/10
            val value = (sky+star).toInt().coerceIn(0,255)
            android.graphics.Color.rgb(value, value, (value+8).coerceAtMost(255))
        }, 0,200,0,0,200,160)
        var applied: BackgroundParameters? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MobileObservatoryTheme {
            BackgroundEditor(bitmap, BackgroundParameters(), onDismiss = {}, onApply = { applied = it })
        } }
        assertTrue(BackgroundParameters().enabled)
        assertEquals(BackgroundMode.AUTO, BackgroundParameters().mode)
        compose.onNodeWithText(app.getString(R.string.stack_bg_auto)).assertIsSelected()
        compose.onNodeWithText(app.getString(R.string.stack_bg_manual)).performClick()
        compose.onNodeWithText(app.getString(R.string.stack_bg_apply)).assertIsNotEnabled()
        for (y in listOf(.12f,.37f,.63f,.88f)) for (x in listOf(.12f,.37f,.63f,.88f)) {
            compose.onNodeWithTag("stack-sample-surface").performTouchInput {
                val scale = min(width/200f, height/160f)
                val w = 200*scale; val h = 160*scale
                click(Offset((width-w)/2 + x*w, (height-h)/2 + y*h))
            }
            compose.onNodeWithTag("stack-sample-loupe").assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.stack_bg_apply)).assertIsNotEnabled()
            if (x == .12f && y == .12f) {
                compose.onNodeWithText(app.getString(R.string.stack_dbe_count, 0)).assertExists()
                compose.onNodeWithTag("stack-sample-loupe").performTouchInput { click(Offset(width*.8f, height/2f)) }
                screenshot("dbe-loupe")
            }
            compose.onNodeWithText(app.getString(R.string.stack_dbe_add)).performClick()
            compose.onNodeWithTag("stack-sample-loupe").assertDoesNotExist()
        }
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.stack_dbe_count, 16)).assertExists()
        assertNull("Preview edits must not mutate a live recipe before Apply", applied)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(app.getString(R.string.stack_dbe_count, 16)).assertExists()
        compose.onNodeWithText(app.getString(R.string.stack_dbe_undo)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.stack_dbe_count, 15)).assertExists()
        screenshot("manual-dbe")
        compose.onNodeWithText(app.getString(R.string.stack_bg_apply)).assertIsEnabled().performClick()
        assertEquals(BackgroundMode.MANUAL, applied!!.mode)
        assertEquals(15, applied!!.samplePoints.size)
        assertTrue("Loupe tap moves the source position away from the synthetic star", applied!!.samplePoints.first().x > .14)
        assertTrue(applied!!.samplePoints.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
    }

    @Suppress("DEPRECATION")
    @Test fun chineseNightModeLoupeAtImageEdgeCanBeCancelledWithoutAddingASample() {
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = compose.activity
        val previous = Configuration(localized.resources.configuration)
        instrumentation.runOnMainSync { localized.resources.updateConfiguration(config, localized.resources.displayMetrics) }
        val bitmap = Bitmap.createBitmap(200,80,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff182033.toInt()) }
        try {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                MobileObservatoryTheme(redNightMode = true) {
                    BackgroundEditor(bitmap, BackgroundParameters(), onDismiss = {}, onApply = {})
                }
            }
        }
        compose.onNodeWithText(localized.getString(R.string.stack_bg_manual)).performClick()
        compose.onNodeWithTag("stack-sample-surface").performTouchInput {
            val scale = min(width/200f,height/80f); val w=200*scale; val h=80*scale
            click(Offset((width-w)/2+w*.01f,(height-h)/2+h*.01f))
        }
        compose.onNodeWithTag("stack-sample-loupe").assertIsDisplayed()
        screenshot("chinese-night-loupe-edge")
        compose.onNode(hasText(localized.getString(R.string.cancel)) and hasAnyAncestor(hasTestTag("stack-sample-loupe-card"))).performClick()
        compose.onNodeWithTag("stack-sample-loupe").assertDoesNotExist()
        compose.onNodeWithText(localized.getString(R.string.stack_dbe_count,0)).assertExists()
        } finally { instrumentation.runOnMainSync { localized.resources.updateConfiguration(previous, localized.resources.displayMetrics) } }
    }

    @Test fun refiningOntoAnExistingSampleDisablesAddUntilMovedAway() {
        val bitmap = Bitmap.createBitmap(200,160,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff182033.toInt()) }
        compose.setContent { MobileObservatoryTheme {
            BackgroundEditor(bitmap, BackgroundParameters(mode = BackgroundMode.MANUAL,
                samplePoints = listOf(BackgroundSamplePoint(.5,.5))), onDismiss = {}, onApply = {})
        } }
        compose.onNodeWithTag("stack-sample-surface").performTouchInput {
            val scale = min(width/200f,height/160f); val w=200*scale; val h=160*scale
            click(Offset((width-w)/2+w*.54f,(height-h)/2+h*.5f))
        }
        compose.onNodeWithText(app.getString(R.string.stack_dbe_add)).assertIsEnabled()
        // The 20-pixel crop around x=108 starts at x=98. Its x=100 pixel is the saved point.
        compose.onNodeWithTag("stack-sample-loupe").performTouchInput {
            val crop = min(width,height).toFloat()
            click(Offset((width-crop)/2+crop*.1f,crop*.5f))
        }
        compose.onNodeWithText(app.getString(R.string.stack_dbe_add)).assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.stack_dbe_count,1)).assertExists()
        compose.onNodeWithTag("stack-sample-loupe").performTouchInput {
            val crop = min(width,height).toFloat()
            click(Offset((width-crop)/2+crop*.95f,crop*.5f))
        }
        compose.onNodeWithText(app.getString(R.string.stack_dbe_add)).assertIsEnabled().performClick()
        compose.onNodeWithText(app.getString(R.string.stack_dbe_count,2)).assertExists()
    }

    @Test fun calibrationIsOptionalAndBiasGuideShowsOneStepAtATimeThenOpensCameraInSer() {
        val store = ViewModelStore()
        lateinit var vm: CameraViewModel
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
            vm.stacking.open(StackMode.LIVE)
        }
        try {
            var cameraReached by mutableStateOf(false)
            compose.setContent { MobileObservatoryTheme {
                if (cameraReached) Text("calibration-camera-reached")
                else StackingScreen(vm, StackMode.LIVE, onBack = {}, onCamera = { cameraReached = true })
            } }
            compose.waitUntil(10_000) { !vm.stacking.calibration.state.value.busy }
            compose.onNodeWithText(app.getString(R.string.stack_cal_entry, app.getString(R.string.stack_cal_none))).performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.stack_cal_optional)).assertIsDisplayed()
            screenshot("optional-calibration")
            compose.onNodeWithText(app.getString(R.string.stack_cal_bias)).performClick()
            compose.onNodeWithText(app.getString(R.string.stack_cal_guide)).performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.stack_cal_bias_prepare)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.stack_cal_bias_settings)).assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.stack_cal_next)).performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.stack_cal_bias_settings)).assertIsDisplayed()
            screenshot("bias-guide")
            compose.onNodeWithText(app.getString(R.string.stack_cal_next)).performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.stack_cal_record_steps)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.stack_cal_go_camera)).performScrollTo().performClick()
            compose.onNodeWithText("calibration-camera-reached").assertIsDisplayed()
            assertTrue(vm.stacking.calibration.state.value.selectedIds.isEmpty())
            assertEquals("SER", vm.recordFormat.value.name)
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: app.getExternalFilesDir("test-artifacts"), "calibration-ui").apply { mkdirs() }
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { image ->
            File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
