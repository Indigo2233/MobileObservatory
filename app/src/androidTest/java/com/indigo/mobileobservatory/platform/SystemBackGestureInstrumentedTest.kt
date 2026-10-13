package com.indigo.mobileobservatory.platform

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.ui.WorkflowTestActivity
import com.indigo.mobileobservatory.ui.screens.CameraScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Emulator evidence only: real SystemUI edge gestures over the actual shell and input fields. */
@RunWith(AndroidJUnit4::class)
class SystemBackGestureInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var store: ViewModelStore
    private lateinit var gestures: SystemEdgeBackGesture
    private var originalAutoCheck: Boolean? = null

    @Before fun startShellWithGestureNavigation() {
        val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        originalAutoCheck = if (prefs.contains("update_auto_check")) prefs.getBoolean("update_auto_check", true) else null
        prefs.edit().putBoolean("update_auto_check", false).commit()
        gestures = SystemEdgeBackGesture(instrumentation)
        store = ViewModelStore()
        lateinit var vm: CameraViewModel
        instrumentation.runOnMainSync { vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java] }
        compose.setContent { MobileObservatoryTheme { CameraScreen(vm) } }
        compose.onNodeWithTag("workspace-CAPTURE").assertIsSelected()
    }

    @After fun restoreNavigationAndPreferences() {
        if (::store.isInitialized) instrumentation.runOnMainSync { store.clear() }
        if (::gestures.isInitialized) gestures.close()
        val edit = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit()
        originalAutoCheck?.let { edit.putBoolean("update_auto_check", it) } ?: edit.remove("update_auto_check")
        edit.commit()
    }

    @Test fun cancelledEdgeBackKeepsThePolarTaskThenCommittedBackClosesOneLevel() {
        openPolar()
        gestures.perform(commit = false)
        compose.onNodeWithText(app.getString(R.string.three_point_polar_alignment)).assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
        gestures.perform(commit = true)
        compose.waitUntil(5000) { compose.onAllNodesWithText(app.getString(R.string.wf_preparation)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.getString(R.string.wf_preparation)).assertIsDisplayed()
        compose.onNodeWithTag("workspace-STAR_MAP").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun edgeBackDismissesTheImeBeforeLeavingTheTaskAndPreservesTheInput() {
        openPolar()
        val latitude = hasSetTextAction() and hasText(app.getString(R.string.latitude_deg))
        compose.onNode(latitude).performScrollTo().performClick().performTextReplacement("31.234")
        compose.waitUntil(5000) { imeVisible() }
        gestures.perform(commit = true)
        compose.waitUntil(5000) { !imeVisible() }
        compose.onNodeWithText(app.getString(R.string.three_point_polar_alignment)).assertIsDisplayed()
        compose.onNode(latitude).assertTextContains("31.234")
        assertFalse(compose.activity.isFinishing)
        gestures.perform(commit = true)
        compose.waitUntil(5000) { compose.onAllNodesWithText(app.getString(R.string.wf_preparation)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.getString(R.string.wf_preparation)).assertIsDisplayed()
    }

    private fun imeVisible() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    private fun openPolar() {
        compose.onNodeWithTag("workspace-STAR_MAP").performClick()
        compose.onNodeWithText(app.getString(if (BuildConfig.STELLARIUM_ENABLED) R.string.wf_star_map_preparation else R.string.wf_preparation)).performClick()
        compose.onNodeWithText(app.getString(R.string.polar_alignment)).performClick()
        compose.onNodeWithText(app.getString(R.string.three_point_polar_alignment)).assertIsDisplayed()
    }
}
