package com.indigo.mobileobservatory.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.screens.PushToFieldDialog
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PushToFieldInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fieldChangesRemainDraftsUntilValidDimensionsAreConfirmed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AtomicReference<Triple<String, String, String>?>(null)
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) PushToFieldDialog("circle", "1.5", "1.0",
                    onDismiss = { visible.value = false }, onConfirm = { shape, width, height ->
                        result.set(Triple(shape, width, height)); visible.value = false
                    })
            }
        }
        compose.onNodeWithTag("push-field-width").performTextClearance()
        compose.onNodeWithTag("push-field-width").performTextInput("0")
        compose.onNodeWithText(context.getString(R.string.wf_push_field_confirm)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertNull("Cancelling must not commit the zero field or change verification tolerance", result.get())
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText(context.getString(R.string.wf_push_field_camera)).performClick()
        compose.onNodeWithTag("push-field-width").performTextClearance()
        compose.onNodeWithTag("push-field-width").performTextInput("2.0")
        compose.onNodeWithTag("push-field-height").performTextClearance()
        compose.onNodeWithTag("push-field-height").performTextInput("0.2")
        assertNull(result.get())
        compose.onNodeWithText(context.getString(R.string.wf_push_field_confirm)).assertIsEnabled().performClick()
        assertEquals(Triple("camera", "2.0", "0.2"), result.get())
    }
}
