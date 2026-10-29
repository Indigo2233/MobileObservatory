package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.screens.GuideScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

internal fun saveGuideScreenshot(name: String) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    val folder = "/sdcard/Download/polar-ui-screenshots"
    val path = "$folder/guide-$name.png"
    shell("mkdir -p $folder")
    shell("screencap -p $path")
    assertEquals(path, shell("ls $path").trim())
}

class GuideSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val prefs get() = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    private val keys = listOf("guide_calibration_steps", "guide_calibration_distance", "guide_calibration_pulse_ms",
        "guide_ra_aggressiveness", "guide_dec_aggressiveness", "guide_min_move_px", "guide_focal_length_mm", "update_auto_check",
        "guide_algorithm", "guide_dec_algorithm", "guide_dec_min_move_px", "guide_hysteresis", "guide_dec_hysteresis",
        "guide_slope_weight", "guide_dec_slope_weight", "guide_fast_switch", "guide_dec_fast_switch",
        "guide_prediction_gain", "guide_period_seconds", "guide_estimate_period", "guide_exposure_factor", "guide_dec_exposure_factor") +
        com.indigo.mobileobservatory.guide.GuideAlgorithm.entries.flatMap {
            listOf("guide_ra_aggressiveness_${it.name}", "guide_dec_aggressiveness_${it.name}",
                "guide_ra_min_move_${it.name}", "guide_dec_min_move_${it.name}") }
    private lateinit var saved: Map<String, Any?>
    private val store = ViewModelStore()
    private lateinit var vm: CameraViewModel

    @Before fun prepare() {
        saved = keys.associateWith { prefs.all[it] }
        prefs.edit().apply { keys.forEach(::remove); putBoolean("update_auto_check", false) }.commit()
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
        }
    }

    @After fun finish() {
        instrumentation.runOnMainSync { store.clear() }
        prefs.edit().apply {
            saved.forEach { (key, value) -> when (value) {
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                else -> remove(key)
            } }
        }.commit()
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun start(fontScale: Float = 1f, night: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MobileObservatoryTheme(redNightMode = night) { Surface(Modifier.fillMaxSize()) { GuideScreen(vm) {} } }
            }
        }
        compose.onNodeWithText(app.getString(R.string.settings)).performClick()
        compose.onNodeWithTag("guide-settings-dialog").assertIsDisplayed()
    }

    private fun category(label: Int) = compose.onNodeWithText(app.getString(label)).performScrollTo().performClick()
    private fun input(id: String, text: String) {
        compose.onNodeWithTag("guide-$id-input").performScrollTo().performTextReplacement(text)
    }
    private fun apply(id: String) = compose.onNodeWithTag("guide-$id-apply").performScrollTo().performClick()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        saveGuideScreenshot("settings-$name")
    }

    @Test fun wideDialogSupportsWholeStepsAndExactMilliseconds() {
        start(); category(R.string.calibration)
        val dialog = compose.onNodeWithTag("guide-settings-dialog").fetchSemanticsNode().boundsInRoot
        val image = compose.onNodeWithTag("guide-image").fetchSemanticsNode().boundsInRoot
        assertTrue("Settings use the available screen width", dialog.width >= image.width * .9f)
        compose.onNodeWithTag("guide-steps-input").assertTextEquals("7")
        input("steps", "7.5")
        compose.onNodeWithTag("guide-steps-apply").assertIsNotEnabled()
        assertEquals(7, vm.guideCalibrationSteps.value)
        input("steps", "11"); apply("steps")
        assertEquals(11, vm.guideCalibrationSteps.value)
        input("steps", "12"); apply("steps")
        assertEquals(12, vm.guideCalibrationSteps.value)
        compose.onNodeWithTag("guide-steps-increase").performScrollTo().performClick()
        compose.onNodeWithTag("guide-steps-input").assertTextEquals("13")
        assertEquals(13, vm.guideCalibrationSteps.value)
        input("pulse", "1234"); apply("pulse")
        assertEquals(1234, vm.guideCalibrationPulseMs.value)
        compose.onNodeWithTag("guide-pulse-increase").performScrollTo().performClick()
        assertEquals(1334, vm.guideCalibrationPulseMs.value)
        screenshot("calibration-portrait")
    }

    @Test fun axisAlgorithmsMinMoveAndHysteresisAreIndependent() {
        instrumentation.runOnMainSync {
            vm.setGuideDecAlgorithm(com.indigo.mobileobservatory.guide.GuideAlgorithm.HYSTERESIS)
            vm.setGuideDecFilterParameters(vm.guideDecFilterParameters.value.copy(hysteresis = .3))
            vm.setGuideDecMinMove(.25f)
        }
        start(); category(R.string.gw_algorithms)
        compose.onNodeWithTag("guide-ra-hysteresis-input").performScrollTo().assertTextContains("10")
        compose.onNodeWithTag("guide-dec-hysteresis-input").performScrollTo().assertTextContains("30")
        input("ra-hysteresis", "20"); apply("ra-hysteresis")
        assertEquals(.2, vm.guideFilterParameters.value.hysteresis, 0.0)
        assertEquals(.3, vm.guideDecFilterParameters.value.hysteresis, 0.0)
        input("min-move", "0.15"); apply("min-move")
        assertEquals(.25f, vm.guideDecMinMovePx.value, 0f)
    }

    @Test fun pixelTravelAcceptsWholeNumbersAndPreciseFractionalSteps() {
        instrumentation.runOnMainSync { vm.setGuideCalibrationDistance(20.003f) }
        start(); category(R.string.calibration)
        compose.onNodeWithTag("guide-distance-input").performScrollTo().assertTextContains("20")
        input("distance", "20")
        compose.onNodeWithTag("guide-distance-apply").assertIsEnabled()
        apply("distance")
        assertEquals(20f, vm.guideCalibrationDistance.value, 0f)
        compose.onNodeWithTag("guide-distance-increase").performScrollTo().performClick()
        compose.onNodeWithTag("guide-distance-input").assertTextContains("21")
        assertEquals(21f, vm.guideCalibrationDistance.value, 0f)
        input("distance", "20,25"); apply("distance")
        assertEquals(20.25f, vm.guideCalibrationDistance.value, 0f)
        compose.onNodeWithTag("guide-distance-decrease").performScrollTo().performClick()
        assertEquals(19.25f, vm.guideCalibrationDistance.value, 0f)
        input("distance", "51")
        compose.onNodeWithTag("guide-distance-apply").assertIsNotEnabled()
        assertEquals(19.25f, vm.guideCalibrationDistance.value, 0f)
    }

    @Test fun percentageAndSubpixelInputsApplyExactValues() {
        start(); category(R.string.gw_algorithms)
        compose.onNodeWithTag("guide-ra-input").performScrollTo().assertTextContains("70")
        input("ra", "80"); apply("ra")
        assertEquals(.8f, vm.guideRaAggressiveness.value, 0f)
        input("min-move", "0.99"); apply("min-move")
        compose.onNodeWithTag("guide-min-move-increase").performScrollTo().performClick()
        assertEquals(1f, vm.guideMinMovePx.value, 0f)
        compose.onNodeWithTag("guide-min-move-input").assertTextContains("1")
        compose.onNodeWithTag("guide-min-move-increase").assertIsNotEnabled()
        input("min-move", "0.15")
        compose.onNodeWithTag("guide-min-move-input").performImeAction()
        assertEquals(.15f, vm.guideMinMovePx.value, 0f)
        screenshot("algorithms")
    }

    @Test fun focalLengthPreservesDecimalsAndCanReturnToPixels() {
        start(); category(R.string.gw_optics)
        input("focal", "200.5"); apply("focal")
        assertEquals(200.5f, vm.guideFocalLength.value, 0f)
        compose.onNodeWithTag("guide-settings-close").performClick()
        compose.onNodeWithText(app.getString(R.string.settings)).performClick()
        category(R.string.gw_optics)
        compose.onNodeWithTag("guide-focal-input").assertTextContains("200.5")
        input("focal", ""); apply("focal")
        assertEquals(0f, vm.guideFocalLength.value, 0f)
    }

    @Test fun landscapeWithLargeTextKeepsCloseAndParameterControlsReachable() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        start(fontScale = 1.5f, night = true); category(R.string.calibration)
        compose.onNodeWithTag("guide-steps-input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("guide-steps-increase").assertIsDisplayed().performClick()
        assertEquals(8, vm.guideCalibrationSteps.value)
        compose.onNodeWithTag("guide-settings-close").assertIsDisplayed()
        screenshot("landscape-large-night")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("guide-settings-dialog").assertDoesNotExist()
        compose.onNodeWithTag("guide-image").assertIsDisplayed()
    }
}
