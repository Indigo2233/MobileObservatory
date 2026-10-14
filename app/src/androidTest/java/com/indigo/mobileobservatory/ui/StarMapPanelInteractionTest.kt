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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.mount.MountMotionState
import com.indigo.mobileobservatory.mount.MountMotionType
import com.indigo.mobileobservatory.ui.screens.StarMapScreen
import com.indigo.mobileobservatory.ui.screens.StarMapTarget
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.screens.consumeStarMapPanelTouches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test

class StarMapPanelInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE)
    private var savedBrowsePrefs = emptyMap<String, Boolean?>()

    @Before fun saveBrowsePreferences() {
        savedBrowsePrefs = listOf("star_map_simulated_landscape", "star_map_follow_phone", "star_map_follow_mount").associateWith {
            if (prefs.contains(it)) prefs.getBoolean(it, false) else null
        }
        prefs.edit().putBoolean("star_map_simulated_landscape", true).putBoolean("star_map_follow_phone", false)
            .putBoolean("star_map_follow_mount", false).commit()
    }

    @After fun restoreBrowsePreferences() {
        val edit = prefs.edit()
        savedBrowsePrefs.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putBoolean(key, value) }
        edit.commit()
    }

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

    @Test fun allOrdinaryControlsHideTogetherAndCanvasTapWakesThem() {
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
        compose.onNodeWithText(context.getString(R.string.target_library_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.wf_star_map_preparation)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.star_map_observing_panel)).assertDoesNotExist()
        compose.onNodeWithTag("star-map-canvas").performTouchInput { click(center) }
        // The tap crosses WebView's renderer, a JS setTimeout and the Android
        // bridge Handler before Compose can animate the chrome. Virtual clock
        // advancement alone does not wait for those real asynchronous queues.
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(32)
            compose.onAllNodesWithText(context.getString(R.string.target_library_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(context.getString(R.string.target_library_title)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.wf_star_map_preparation)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, targetsOpened)
            assertEquals(1, preparationOpened)
        }
    }

    @Test fun expandedTargetDetailsSuspendIdleTimerDuringScrollAndActionsThenRestartOnClose() {
        var pushes = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 640.dp, height = 300.dp)) {
                    StarMapScreen(
                        mountCoordinates = null, mountSite = null,
                        selectedObservationTarget = StarMapTarget("Test target", 2.0, 30.0, "J2000"),
                        mountConnected = false, mountBusy = false, onGoto = {}, onBack = {},
                        onPushTo = { pushes++ }
                    )
                }
            }
        }
        waitForEngine()
        // Open the actual More details action on the short landscape target card.
        compose.onNodeWithText(context.getString(R.string.wf_star_map_target_details)).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText(context.getString(R.string.sequence_sensor_angle)).performScrollTo().assertIsDisplayed()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText(context.getString(R.string.push_to_title)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, pushes) }
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText(context.getString(R.string.wf_star_map_hide_details)).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("star-map-target-card").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("star-map-target-card").assertDoesNotExist()
    }

    @Test fun pressAcrossIdleDeadlineCancelsOldTimerAndReleaseGetsACompleteNewInterval() {
        compose.setContent {
            MaterialTheme {
                StarMapScreen(mountCoordinates = null, mountSite = null, mountConnected = false,
                    mountBusy = false, onGoto = {}, onBack = {})
            }
        }
        waitForEngine()
        compose.mainClock.autoAdvance = false
        val library = context.getString(R.string.target_library_title)
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithText(library).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText(library).assertIsDisplayed()
        compose.onNodeWithText(library).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(library).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithText(library).assertDoesNotExist()
    }

    @Test fun landscapeToggleIsNamedPersistedAndPanelCanCloseOnShortLargeFontScreen() {
        val visible = mutableStateOf(true)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    androidx.compose.ui.platform.LocalDensity.current.density, 1.6f)
            ) {
                MaterialTheme {
                    Box(Modifier.size(width = 640.dp, height = 300.dp)) {
                        if (visible.value) StarMapScreen(
                            mountCoordinates = null, mountSite = null, mountConnected = false,
                            mountBusy = false, onGoto = {}, onBack = {})
                    }
                }
            }
        }
        waitForEngine()
        compose.onNodeWithContentDescription(context.getString(R.string.star_map_sky_panel)).performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("sky-landscape").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("sky-landscape").assertIsOff()
        compose.runOnIdle { assertFalse(prefs.getBoolean("star_map_simulated_landscape", true)) }
        // The bottom panel-toggle row retains space even when content is taller than the viewport.
        compose.onNodeWithContentDescription(context.getString(R.string.star_map_sky_panel)).assertIsDisplayed().performClick()
        compose.onNodeWithTag("sky-landscape").assertDoesNotExist()
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { visible.value = true }
        waitForEngine()
        compose.onNodeWithContentDescription(context.getString(R.string.star_map_sky_panel)).performClick()
        compose.onNodeWithTag("sky-landscape").performScrollTo().assertIsOff()
    }

    @Test fun phoneBrowseSwitchHasAccessibleLabelAndNeverEnablesUnverifiedOpticalFollow() {
        compose.setContent {
            MaterialTheme {
                StarMapScreen(mountCoordinates = null, mountSite = null, mountConnected = false,
                    mountBusy = false, onGoto = {}, onBack = {})
            }
        }
        waitForEngine()
        compose.onNodeWithContentDescription(context.getString(R.string.star_map_observing_panel)).performClick()
        compose.onNodeWithTag("sky-phone-follow").assertTextEquals(context.getString(R.string.sky_phone_follow))
        val available = (context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager)
            .getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) != null
        if (available) {
            compose.onNodeWithTag("sky-phone-follow").assertIsEnabled().assertIsOff().performClick()
            compose.onNodeWithTag("sky-phone-follow").assertIsOn()
            compose.runOnIdle { assertTrue(prefs.getBoolean("star_map_follow_phone", false)) }
            compose.onNodeWithTag("pointing-follow").performScrollTo().assertIsOff().assertIsNotEnabled()
            compose.runOnIdle { assertFalse(prefs.getBoolean("star_map_follow_mount", false)) }
        } else {
            compose.onNodeWithTag("sky-phone-follow").assertIsNotEnabled().assertIsOff()
            compose.onNodeWithText(context.getString(R.string.sky_phone_follow_unavailable)).assertIsDisplayed()
        }
    }

    private fun waitForEngine() {
        val readyControl = context.getString(R.string.star_map_use_center)
        compose.waitUntil(45_000) {
            compose.mainClock.advanceTimeBy(32)
            compose.onAllNodesWithText(readyControl).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(500)
    }
}
