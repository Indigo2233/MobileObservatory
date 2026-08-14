package com.indigo.mobileobservatory.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indigo.mobileobservatory.camera.GainCapability
import com.indigo.mobileobservatory.camera.GainValueNormalizer
import com.indigo.mobileobservatory.ui.components.GainControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GainControlTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun invalidTextRestoresThePreviousValue() {
        val submitted = mutableListOf<Float>()
        compose.setContent {
            MaterialTheme {
                GainControl(
                    capability = GainCapability(min = 0f, max = 300f, defaultValue = 100f),
                    gain = 100f,
                    onGainChange = { submitted += it }
                )
            }
        }

        compose.onNodeWithTag("gain_input").performTextReplacement("abc")
        compose.onNodeWithTag("gain_input").performImeAction()
        compose.onNodeWithTag("gain_input").assertTextContains("100")
        compose.onNodeWithText("Enter a valid number").assertIsDisplayed()
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun isoSliderOnlyCommitsLegalValues() {
        val submitted = mutableListOf<Float>()
        val capability = GainValueNormalizer.isoCapability(
            allowedValues = listOf(100f, 200f, 400f, 800f),
            current = 100f
        )
        compose.setContent {
            MaterialTheme {
                GainControl(
                    capability = capability,
                    gain = 100f,
                    onGainChange = { submitted += it }
                )
            }
        }

        compose.onNodeWithTag("gain_input").performTextReplacement("520")
        compose.onNodeWithTag("gain_input").performImeAction()

        assertEquals(listOf(400f), submitted)
    }

    @Test
    fun readOnlyRangeDisablesInput() {
        compose.setContent {
            MaterialTheme {
                GainControl(
                    capability = GainCapability(min = 100f, max = 100f, defaultValue = 100f),
                    gain = 100f,
                    onGainChange = {}
                )
            }
        }

        compose.onNodeWithTag("gain_input").assertIsNotEnabled()
        compose.onNodeWithTag("gain_slider").assertIsNotEnabled()
    }
}
