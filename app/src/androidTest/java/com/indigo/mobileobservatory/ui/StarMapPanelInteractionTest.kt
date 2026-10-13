package com.indigo.mobileobservatory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.mount.MountMotionState
import com.indigo.mobileobservatory.mount.MountMotionType
import com.indigo.mobileobservatory.ui.screens.StarMapScreen
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.screens.consumeStarMapPanelTouches
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StarMapPanelInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun panelPaddingConsumesTapWhileChildControlsRemainOperable() {
        var canvasTaps = 0
        var controlTaps = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { canvasTaps++ })
                    Card(Modifier.size(240.dp).testTag("panel").consumeStarMapPanelTouches()) {
                        Column {
                            TextButton(onClick = { controlTaps++ }) { Text("Panel control") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("panel").performTouchInput {
            click(Offset(width * .9f, height * .9f))
        }
        compose.runOnIdle {
            assertEquals(0, canvasTaps)
            assertEquals(0, controlTaps)
        }
        compose.onNodeWithText("Panel control").performClick()
        compose.runOnIdle {
            assertEquals(0, canvasTaps)
            assertEquals(1, controlTaps)
        }
    }

    @Test fun stopRemainsAboveWebViewAndCanvasTapDoesNotActivateIt() {
        var stops = 0
        compose.setContent {
            MaterialTheme {
                StarMapScreen(
                    mountCoordinates = null, mountSite = null,
                    mountConnected = true, mountBusy = true,
                    mountMotionState = MountMotionState(MountMotionType.GOTO, "GOTO", holdStop = true),
                    onGoto = {}, onStopMount = { stops++ }, onBack = {}
                )
            }
        }
        compose.onNodeWithText("STOP").assertIsDisplayed()
        compose.onRoot().performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, stops) }
        compose.onNodeWithText("STOP").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun observingEntriesRemainVisibleAfterOtherChromeTimesOut() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var targetsOpened = 0
        var preparationOpened = 0
        compose.setContent {
            MaterialTheme {
                StarMapScreen(
                    mountCoordinates = null, mountSite = null,
                    mountConnected = false, mountBusy = false, onGoto = {}, onBack = {},
                    onOpenTargets = { targetsOpened++ }, onOpenPreparation = { preparationOpened++ }
                )
            }
        }
        val optionalControl = context.getString(R.string.star_map_use_center)
        compose.waitUntil(45_000) {
            compose.onAllNodesWithText(optionalControl).fetchSemanticsNodes().isNotEmpty()
        }
        // LaunchedEffect's idle delay and AnimatedVisibility share the Compose test clock.
        // waitUntil advances wall time but does not drive a suspended virtual-time delay.
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(optionalControl).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.target_library_title)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.wf_star_map_preparation)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, targetsOpened)
            assertEquals(1, preparationOpened)
        }
    }
}
