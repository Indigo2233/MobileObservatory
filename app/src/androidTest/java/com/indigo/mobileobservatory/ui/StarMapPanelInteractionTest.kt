package com.indigo.mobileobservatory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
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

    @Test fun blankTapImmediatelyHidesAllControlsAndDismissesPanelWithoutLosingTarget() {
        var selection: StarMapTarget? = null
        compose.setContent {
            MaterialTheme {
                StarMapScreen(
                    mountCoordinates = null, mountSite = null,
                    mountConnected = false, mountBusy = false, onGoto = {}, onBack = {},
                    onTargetSelected = { selection = it }
                )
            }
        }
        waitForEngine()
        // Select an arbitrary point in the default empty horizon view. A catalog
        // position can acquire a star under the tap as asynchronous tiles arrive;
        // tapping that star would correctly select it instead of being a blank tap.
        compose.onNodeWithText(context.getString(R.string.star_map_use_center)).performClick()
        compose.waitUntil(10_000) { selection != null }
        val originalSelection = compose.runOnIdle { requireNotNull(selection) }
        compose.onNodeWithTag("star-map-target-card").assertIsDisplayed()
        tapBlankAndWaitForControls(visible = false)
        compose.onNodeWithTag("star-map-target-card").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.star_map_search)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.star_map_observing_panel)).assertDoesNotExist()
        compose.onNodeWithTag("star-map-controls-lock").assertDoesNotExist()
        tapBlankAndWaitForControls(visible = true)
        compose.onNodeWithTag("star-map-target-card").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.wf_star_map_current_target)).performClick()
        compose.onNodeWithTag("star-map-target-card").assertIsDisplayed()

        compose.onNodeWithContentDescription(context.getString(R.string.star_map_sky_panel)).performClick()
        compose.onNodeWithTag("sky-landscape").performScrollTo().assertIsDisplayed()
        tapBlankAndWaitForControls(visible = false)
        compose.onNodeWithTag("sky-landscape").assertDoesNotExist()
        tapBlankAndWaitForControls(visible = true)
        compose.onNodeWithTag("sky-landscape").assertDoesNotExist()
        compose.onNodeWithTag("star-map-target-card").assertDoesNotExist()
        // No remaining layer: this third tap must hide now, not restart a four-second timer.
        tapBlankAndWaitForControls(visible = false)
        compose.runOnIdle { assertEquals(originalSelection, selection) }
    }

    @Test fun quickLockPreventsTimeoutButAllowsBlankTapAndUnlockRestartsFullTimer() {
        compose.setContent {
            MobileObservatoryTheme {
                Surface {
                StarMapScreen(mountCoordinates = null, mountSite = null, mountConnected = false,
                    mountBusy = false, onGoto = {}, onBack = {})
                }
            }
        }
        waitForEngine()
        compose.onNodeWithTag("star-map-controls-lock").assertIsOff().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.sky_controls_unpin)).assertIsOn()
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText(context.getString(R.string.target_library_title)).assertIsDisplayed()
        compose.onNodeWithTag("star-map-location").assertTextEquals(context.getString(R.string.sky_set_location))
        val screenshot = java.io.File(context.getExternalFilesDir(null), "sky-controls-evidence/pinned.png")
        screenshot.parentFile?.mkdirs()
        screenshot.outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
        tapBlankAndWaitForControls(visible = false)
        tapBlankAndWaitForControls(visible = true)
        compose.onNodeWithTag("star-map-controls-lock").assertIsOn()
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithTag("star-map-controls-lock").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.sky_controls_pin)).assertIsOff()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("star-map-controls-lock").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("star-map-controls-lock").assertDoesNotExist()
    }

    @Test fun quickLockSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                StarMapScreen(mountCoordinates = null, mountSite = null, mountConnected = false,
                    mountBusy = false, onGoto = {}, onBack = {})
            }
        }
        waitForEngine()
        compose.onNodeWithTag("star-map-controls-lock").performClick()
        restoration.emulateSavedInstanceStateRestore()
        waitForEngine()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("star-map-controls-lock").assertIsDisplayed().assertIsOn()
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

    private fun tapBlankAndWaitForControls(visible: Boolean) {
        val before = compose.mainClock.currentTime
        // Stay to the right of the expanded left panel and below the search tools.
        compose.onNodeWithTag("star-map-canvas").performTouchInput { click(Offset(width * .95f, height * .4f)) }
        // Wait for the actual WebView/JS/Android bridge, without advancing enough
        // virtual time for the idle timeout to make an immediate-hide test pass.
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(16)
            val shown = compose.onAllNodesWithText(context.getString(R.string.target_library_title))
                .fetchSemanticsNodes().isNotEmpty()
            shown == visible
        }
        compose.mainClock.advanceTimeBy(300)
        assertTrue("Blank tap must act before the idle timeout", compose.mainClock.currentTime - before < 2_000)
    }
}
