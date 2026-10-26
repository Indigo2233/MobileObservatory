package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.screens.CameraScreen
import com.indigo.mobileobservatory.ui.screens.GuideScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/** Real guide page and navigation owner, before any camera or mount is connected. */
@RunWith(AndroidJUnit4::class)
class GuideEntryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var store: ViewModelStore
    private lateinit var vm: CameraViewModel
    private var autoCheck: Boolean? = null

    @Before fun prepare() {
        val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        autoCheck = if (prefs.contains("update_auto_check")) prefs.getBoolean("update_auto_check", true) else null
        prefs.edit().putBoolean("update_auto_check", false).commit()
        store = ViewModelStore()
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
        }
    }

    @After fun close() {
        instrumentation.runOnMainSync { store.clear() }
        val edit = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit()
        autoCheck?.let { edit.putBoolean("update_auto_check", it) } ?: edit.remove("update_auto_check")
        edit.commit()
    }

    @Test fun disconnectedGuidePageRendersImagePlotsAndInitialStatistics() {
        compose.setContent { MobileObservatoryTheme {
            Surface(Modifier.fillMaxSize()) { GuideScreen(vm, onBack = {}) }
        } }
        compose.onNodeWithTag("guide-image").assertExists()
        compose.onNodeWithTag("guide-graph").assertExists()
        compose.onNodeWithTag("guide-scatter").assertExists()
        compose.onNodeWithText(app.getString(R.string.gw_devices)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.gw_start_exposure)).assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.gw_auto_select)).assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.gw_start_guiding)).assertIsNotEnabled()
    }

    @Test fun wideGuidePageRendersTheEmptyStarPanelBeforeConnecting() {
        compose.setContent { MobileObservatoryTheme {
            Box(Modifier.requiredSize(1100.dp, 500.dp)) { GuideScreen(vm, onBack = {}) }
        } }
        compose.onNodeWithText(app.getString(R.string.guide_star)).assertExists()
        compose.onNodeWithTag("guide-graph").assertExists()
    }

    @Test fun toolsGuideEntryOpensTheRealPageAndReturnsToTools() {
        compose.setContent { MobileObservatoryTheme {
            Surface(Modifier.fillMaxSize()) { CameraScreen(vm, redNightMode = false, onRedNightModeChange = {}) }
        } }
        compose.onNodeWithTag("workspace-TOOLS").performClick()
        compose.onNodeWithTag("tool-guide").performScrollTo().performClick()
        compose.onNodeWithTag("guide-image").assertExists()
        compose.onNodeWithTag("guide-graph").assertExists()
        compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        compose.onNodeWithTag("tool-guide").assertExists()
    }

    @Test fun shortLandscapeKeepsALargeCameraBesideCompactPlots() {
        compose.setContent { MobileObservatoryTheme {
            Box(Modifier.requiredSize(780.dp, 300.dp)) { GuideScreen(vm, onBack = {}) }
        } }
        val image = compose.onNodeWithTag("guide-image").getUnclippedBoundsInRoot()
        val plots = compose.onNodeWithTag("guide-plots").getUnclippedBoundsInRoot()
        val graph = compose.onNodeWithTag("guide-graph").getUnclippedBoundsInRoot()
        assertTrue("The camera should retain most of the workbench width", image.width > plots.width * 1.4f)
        assertTrue("Plots should sit beside the camera", plots.left >= image.right)
        assertTrue("The camera must stay substantially taller than the graph", image.height > graph.height * 2.5f)
        compose.onNodeWithTag("guide-star-summary").assertExists()
    }

    @Test fun portraitKeepsCompactPlotsBelowALargeCamera() {
        compose.setContent { MobileObservatoryTheme {
            Box(Modifier.requiredSize(360.dp, 720.dp)) { GuideScreen(vm, onBack = {}) }
        } }
        val image = compose.onNodeWithTag("guide-image").getUnclippedBoundsInRoot()
        val plots = compose.onNodeWithTag("guide-plots").getUnclippedBoundsInRoot()
        val graph = compose.onNodeWithTag("guide-graph").getUnclippedBoundsInRoot()
        assertTrue("Portrait diagnostics must remain below the camera", plots.top >= image.bottom)
        assertTrue("The preview should receive most of the available height", image.height > 360.dp)
        assertTrue("The graph should remain compact", image.height > graph.height * 3f)
    }

    @Test fun guideStatusFloatsInsideThePreview() {
        compose.setContent { MobileObservatoryTheme {
            Box(Modifier.requiredSize(780.dp, 300.dp)) { GuideScreen(vm, onBack = {}) }
        } }
        val image = compose.onNodeWithTag("guide-image").getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag("guide-status").getUnclippedBoundsInRoot()
        assertTrue("Status must overlay the image", status.top > image.top && status.bottom < image.bottom)
        assertTrue("Status must stay inside the preview width", status.left >= image.left && status.right <= image.right)
        assertTrue("Only the toolbar should reserve space above the image", image.top < 64.dp)
    }

    @Test fun largeFontLandscapeKeepsTheCameraAndAllDiagnostics() {
        compose.setContent { MobileObservatoryTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.8f)) {
                Box(Modifier.requiredSize(780.dp, 320.dp)) { GuideScreen(vm, onBack = {}) }
            }
        } }
        val image = compose.onNodeWithTag("guide-image").getUnclippedBoundsInRoot()
        val graph = compose.onNodeWithTag("guide-graph").getUnclippedBoundsInRoot()
        assertTrue("Large text must leave the image usable", image.height > graph.height * 2f)
        compose.onNodeWithContentDescription(app.getString(R.string.gw_plot_settings)).assertExists()
        compose.onNodeWithContentDescription(app.getString(R.string.clear_graph)).assertExists()
        compose.onNodeWithTag("guide-scatter").assertExists()
    }

    @Test fun plotSettingsAndStarDetailsRemainReachableAndRestoreTheirState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MobileObservatoryTheme {
            Surface(Modifier.fillMaxSize()) { GuideScreen(vm, onBack = {}) }
        } }
        compose.onNodeWithContentDescription(app.getString(R.string.gw_plot_settings)).performClick()
        compose.onNodeWithTag("guide-samples-50").performClick().assertIsSelected()
        compose.onNodeWithTag("guide-pulses").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("±4 px").performScrollTo().performClick()
        compose.onNodeWithText("±8 px").performClick()
        compose.onNodeWithText(app.getString(R.string.close)).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("guide-sample-count").assertTextEquals(app.getString(R.string.gw_sample_count, 0, 50))
        compose.onNodeWithContentDescription(app.getString(R.string.gw_plot_settings)).performClick()
        compose.onNodeWithTag("guide-samples-50").assertIsSelected()
        compose.onNodeWithTag("guide-pulses").performScrollTo().assertIsOff()
        compose.onNodeWithText("±8 px").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.close)).performClick()
        compose.onNodeWithTag("guide-star-summary").performClick()
        compose.onNodeWithTag("guide-star-details").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.close)).performClick()
        compose.onNodeWithTag("guide-star-summary").assertIsDisplayed()
    }
}
