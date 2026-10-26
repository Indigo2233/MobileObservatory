package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
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
}
