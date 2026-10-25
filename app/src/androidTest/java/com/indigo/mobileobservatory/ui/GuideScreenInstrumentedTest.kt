package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.screens.GuideScreen
import com.indigo.mobileobservatory.ui.screens.CameraScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Exercises the complete screen, including controls, formatting and the empty graph. */
class GuideScreenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val store = ViewModelStore()
    private lateinit var vm: CameraViewModel

    @Before fun prepare() {
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
        }
    }

    @After fun finish() {
        instrumentation.runOnMainSync { store.clear() }
    }

    @Test fun openingGuidingAndItsPanelsWithoutConnectedDevices() {
        val open = mutableStateOf(false)
        compose.setContent { MobileObservatoryTheme {
            Box(Modifier.fillMaxSize()) {
                if (open.value) GuideScreen(vm) { open.value = false }
                else Button(onClick = { open.value = true }, modifier = Modifier.testTag("open-guiding")) { Text("Open guiding") }
            }
        } }
        repeat(2) {
            compose.onNodeWithTag("open-guiding").performClick()
            compose.onNodeWithTag("guide-image").assertIsDisplayed()
            compose.onNodeWithTag("guide-graph").assertIsDisplayed()
            compose.onNodeWithTag("guide-scatter").assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.settings)).performClick()
            compose.onNodeWithText(app.getString(R.string.gw_optics)).performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.gw_scale_help)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.close)).performClick()
            compose.onNodeWithText(app.getString(R.string.gw_devices)).performClick()
            compose.onNodeWithText(app.getString(R.string.tab_mount)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.close)).performClick()
            compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        }
    }

    @Test fun openingGuidingThroughTheChineseApplicationShell() {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = ContextThemeWrapper(compose.activity, 0).apply { applyOverrideConfiguration(config) }
        compose.setContent { CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
            MobileObservatoryTheme { CameraScreen(vm) }
        } }
        compose.onNodeWithTag("workspace-TOOLS").performClick()
        repeat(2) {
            compose.onNodeWithTag("tool-guide").performScrollTo().performClick()
            compose.onNodeWithTag("guide-image").assertIsDisplayed()
            compose.onNodeWithTag("guide-graph").assertIsDisplayed()
            compose.onNodeWithContentDescription(localized.getString(R.string.back)).performClick()
            compose.onNodeWithTag("workspace-TOOLS").assertIsSelected()
        }
    }
}
