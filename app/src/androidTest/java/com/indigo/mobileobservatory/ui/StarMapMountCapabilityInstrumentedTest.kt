package com.indigo.mobileobservatory.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.pointing.PointingMountCapabilities
import com.indigo.mobileobservatory.ui.screens.StarMapScreen
import com.indigo.mobileobservatory.ui.screens.StarMapTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

class StarMapMountCapabilityInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private val target = StarMapTarget("WF capability target", 5.0, 20.0, "J2000")
    @Before fun freezeChromeClock() { org.junit.Assume.assumeTrue(BuildConfig.STELLARIUM_ENABLED); compose.mainClock.autoAdvance = false }
    @After fun restoreClock() { compose.mainClock.autoAdvance = true }
    private fun ready() {
        compose.waitUntil(45_000) {
            compose.mainClock.advanceTimeBy(32)
            compose.onAllNodesWithText(label(R.string.star_map_use_center)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun readOnlyTargetKeepsPushAsPrimaryAndHidesEveryCoordinateWriteAction() {
        var pushes = 0
        compose.setContent {
            MaterialTheme {
                StarMapScreen(null, null, mountConnected = true, mountBusy = false,
                    mountPointingCapabilities = PointingMountCapabilities(readCoordinates = true),
                    selectedObservationTarget = target, onGoto = {}, onPushTo = { pushes++ }, onBack = {})
            }
        }
        ready()
        compose.onNodeWithText(label(R.string.push_to_title)).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.goto_label)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.sync_label)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.precision_goto_label)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, pushes) }
    }

    @Test fun readOnlyObservingPanelKeepsReadAndStopWithoutManualOrHomeControls() {
        var stops = 0
        compose.setContent {
            MaterialTheme {
                StarMapScreen(null, null, mountConnected = true, mountBusy = true,
                    mountPointingCapabilities = PointingMountCapabilities(readCoordinates = true),
                    onGoto = {}, onStopMount = { stops++ }, onBack = {})
            }
        }
        ready()
        compose.onNodeWithContentDescription(label(R.string.star_map_observing_panel)).performClick()
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithContentDescription(label(R.string.move_north)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.home)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.wf_center_pointing)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.wf_follow_refresh)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.stop_mount)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun syncCapabilityIsIndependentOfGotoAndRevocationDismissesPendingWrite() {
        val capabilities = mutableStateOf(PointingMountCapabilities(readCoordinates = true, sync = true))
        var syncs = 0
        compose.setContent {
            MaterialTheme {
                StarMapScreen(null, null, mountConnected = true, mountBusy = false,
                    mountPointingCapabilities = capabilities.value,
                    selectedObservationTarget = target, onGoto = {}, onSync = { syncs++ }, onBack = {})
            }
        }
        ready()
        compose.onNodeWithText(label(R.string.goto_label)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.sync_label)).assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText(label(R.string.execute_sync)).assertIsDisplayed()
        compose.runOnIdle { capabilities.value = PointingMountCapabilities(readCoordinates = true) }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText(label(R.string.execute_sync)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.sync_label)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, syncs) }
    }
}
