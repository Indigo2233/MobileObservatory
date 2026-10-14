package com.indigo.mobileobservatory.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.stacking.*
import com.indigo.mobileobservatory.ui.screens.StackingScreen
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StackEditorRestoreInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoringSameStackKeepsTheOpenBackgroundEditorAndItsUnappliedDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val store = ViewModelStore()
        lateinit var vm: CameraViewModel
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
            // Display fixture only: no synthetic observation or calibration is saved.
            val product = StackProduct(LinearImage(32, 32, 1, FloatArray(1024) { .1f }), FloatArray(1024) { 1f }, emptyMap())
            vm.stacking.javaClass.getDeclaredField("liveProduct").apply { isAccessible = true }.set(vm.stacking, product)
            vm.stacking.open(StackMode.LIVE)
        }
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent { MaterialTheme { Surface { StackingScreen(vm, StackMode.LIVE, onBack = {}) } } }
            compose.onNodeWithText(app.getString(R.string.stack_bg_entry, app.getString(R.string.stack_bg_auto)))
                .performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.stack_bg_manual)).performClick().assertIsSelected()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText(app.getString(R.string.stack_background)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.stack_bg_manual)).assertIsSelected()
            compose.runOnIdle { assertEquals(BackgroundMode.AUTO, vm.stacking.processing.value.background.mode) }
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }
}
