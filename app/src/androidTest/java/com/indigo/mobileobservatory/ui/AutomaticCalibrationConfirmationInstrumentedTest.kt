package com.indigo.mobileobservatory.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.*
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.pointing.*
import com.indigo.mobileobservatory.ui.screens.AutomaticCalibrationConfirmation
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AutomaticCalibrationConfirmationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val plan = AutomaticCalibrationPlan(listOf(
        AutomaticCalibrationTarget(PhoneCatalogStar(10.0, 20.0, 1.0, 1, "First target"), 40.0, 130.0),
        AutomaticCalibrationTarget(PhoneCatalogStar(30.0, 20.0, 1.0, 2, "Independent check"), 50.0, 140.0)),
        ObserverSite(31.0, 121.0), 1_700_000_000_000, AutomaticCalibrationConstraints())

    @Test fun targetPreviewCancelsWithoutStartingMotionAndCommitRunsOnce() {
        val context = compose.activity
        var moves = 0; var dismissals = 0
        var show by mutableStateOf(true)
        compose.setContent { MobileObservatoryTheme {
            if (show) AutomaticCalibrationConfirmation(plan, onConfirm = { moves++; show = false }, onDismiss = { dismissals++; show = false })
        } }
        compose.onNodeWithText("First target", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Independent check", substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.wfp_auto_confirmation)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(0, moves)
        assertEquals(1, dismissals)
        compose.onNodeWithText(context.getString(R.string.wfp_auto_start)).assertDoesNotExist()
        compose.runOnUiThread { show = true }
        compose.onNodeWithText(context.getString(R.string.wfp_auto_start)).performClick()
        assertEquals(1, moves)
        compose.onNodeWithText(context.getString(R.string.wfp_auto_start)).assertDoesNotExist()
    }
    @Test fun readinessLossDisablesTheMoveActionWhileCancelRemainsAvailable() {
        var moves = 0; var dismissals = 0
        var enabled by mutableStateOf(true)
        compose.setContent { MobileObservatoryTheme {
            AutomaticCalibrationConfirmation(plan, enabled = enabled, onConfirm = { moves++ }, onDismiss = { dismissals++ })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.wfp_auto_start)).assertIsEnabled()
        compose.runOnUiThread { enabled = false }
        compose.onNodeWithText(compose.activity.getString(R.string.wfp_auto_start)).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        assertEquals(0, moves)
        assertEquals(1, dismissals)
    }
}
