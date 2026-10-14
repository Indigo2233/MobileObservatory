package com.indigo.mobileobservatory.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.indigo.mobileobservatory.stacking.SerCfaInterpretation
import com.indigo.mobileobservatory.ui.screens.SerCfaSelector
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SerCfaSelectorInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyRawPatternIsSelectableAndCompletedAnalysisRequiresExplicitChange() {
        var selected by mutableStateOf(SerCfaInterpretation.AUTO)
        var needsConfirmation by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                SerCfaSelector(selected, true, needsConfirmation, onSelect = { selected = it })
            }
        }
        for (mode in SerCfaInterpretation.values()) {
            compose.onNodeWithTag("stack-cfa-selector").assertIsEnabled().performClick()
            compose.onNodeWithTag("stack-cfa-${mode.name}").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(mode, selected) }
        }
        compose.runOnIdle { needsConfirmation = true }
        compose.onNodeWithTag("stack-cfa-selector").performClick()
        compose.onNodeWithTag("stack-cfa-RGGB").performClick()
        compose.runOnIdle { assertEquals(SerCfaInterpretation.GBRG, selected) }
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(com.indigo.mobileobservatory.R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(SerCfaInterpretation.GBRG, selected) }
    }
}
