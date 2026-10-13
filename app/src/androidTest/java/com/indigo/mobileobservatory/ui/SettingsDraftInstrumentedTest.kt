package com.indigo.mobileobservatory.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.components.rememberSettingsExitState
import com.indigo.mobileobservatory.ui.screens.DeviceSettingsPresentation
import com.indigo.mobileobservatory.ui.screens.SettingsDraftEditor
import com.indigo.mobileobservatory.ui.screens.SettingsField
import com.indigo.mobileobservatory.ui.settings.SettingsDraftViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDraftInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val model = SettingsDraftViewModel(SavedStateHandle())
    private var exited by mutableStateOf(false)
    private var device by mutableStateOf(presentation("usb:1"))
    private var writes = 0

    @Test fun backWithoutEditsDoesNotAskToSave() {
        content()
        back()
        compose.onNodeWithText("device-home").assertIsDisplayed()
        assertEquals(0, writes)
    }

    @Test fun invalidTextIsRetainedAndExitOffersKeepAndDiscard() {
        content()
        field().performTextReplacement("-")
        back()
        compose.onNodeWithText(text(R.string.wf_edit_save_leave)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.wf_edit_keep)).performClick()
        field().assertTextContains("-")
        back()
        compose.onNodeWithText(text(R.string.wf_edit_discard)).performClick()
        compose.onNodeWithText("device-home").assertIsDisplayed()
        assertEquals(0, writes)
    }

    @Test fun saveAndLeaveWritesOnlyAfterExplicitChoice() {
        content()
        field().performTextReplacement("25")
        assertEquals(0, writes)
        back()
        compose.onNodeWithText(text(R.string.wf_edit_save_leave)).performClick()
        compose.onNodeWithText("device-home").assertIsDisplayed()
        assertEquals(1, writes)
    }

    @Test fun configurationRestorationKeepsDraftAndPendingExit() {
        val restore = StateRestorationTester(compose)
        content(restore)
        field().performTextReplacement("25")
        back()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.wf_edit_keep)).performClick()
        field().assertTextContains("25")
        assertEquals(0, writes)
    }

    @Test fun changingPhysicalDeviceCannotApplyOldDraftWithoutExplicitRetargeting() {
        content()
        field().performTextReplacement("25")
        compose.runOnIdle { device = presentation("usb:2") }
        field().assertTextContains("25")
        compose.onNodeWithTag("device-settings-save").assertIsNotEnabled()
        assertEquals("usb:1", model.draft.value?.deviceKey)
        compose.onNodeWithText(text(R.string.wf_edit_use_current_device, "test-focuser")).performClick()
        compose.onNodeWithTag("device-settings-save").assertIsEnabled().performClick()
        assertEquals(1, writes)
        assertEquals("usb:2", model.draft.value?.deviceKey)
    }

    private fun content(restoration: StateRestorationTester? = null) {
        val ui: @Composable () -> Unit = {
            MaterialTheme {
                if (exited) Text("device-home")
                else SettingsDraftEditor(device, model, rememberSettingsExitState(), onBack = { exited = true }) { draft, success, _ ->
                    writes++
                    success(draft.fields)
                }
            }
        }
        if (restoration == null) compose.setContent(ui) else restoration.setContent(ui)
    }
    private fun field() = compose.onNodeWithTag("device-setting-fine")
    private fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun text(id: Int, vararg arguments: Any) = compose.activity.getString(id, *arguments)
    private fun presentation(key: String) = DeviceSettingsPresentation(key, "test-focuser", true, mapOf("fine" to "10"),
        listOf(SettingsField("fine", "Fine step", valid = { it.toIntOrNull()?.let { n -> n > 0 } == true })))
}
