package com.indigo.mobileobservatory.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.stacking.SerCfaInterpretation
import com.indigo.mobileobservatory.ui.components.CameraCfaDialog
import com.indigo.mobileobservatory.ui.components.CameraPreviewTools
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraCfaDialogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun previewOverflowOffersCfaAndCancelDoesNotApplyAStagedChoice() {
        var applied: SerCfaInterpretation? = null
        compose.setContent {
            var open by rememberSaveable { mutableStateOf(false) }
            MaterialTheme {
                CameraPreviewTools(connected=true, showPanel=false, showOverlayPanel=false,
                    focusAssistEnabled=false, showCenterMarker=false, lp750Enabled=false,
                    lp750Available=true, selectedTarget=null, currentFrameSolveBusy=false, onFitToView={},
                    onTogglePanel={}, onToggleOverlay={}, onToggleFocusAssist={}, onToggleCenterMarker={},
                    onToggleLp750={}, onSolveCurrentFrame={}, onChooseTarget={}, onCfaSettings={ open=true })
                if (open) CameraCfaDialog(SerCfaInterpretation.AUTO, true, false, false, { null },
                    onApply={ value, _ -> applied=value; open=false }, onDismiss={ open=false })
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.camera_preview_tools)).performClick()
        compose.onNodeWithText(context.getString(R.string.camera_cfa_title)).performClick()
        compose.onNodeWithTag("camera-cfa-BGGR").performScrollTo().performClick()
        compose.onNodeWithTag("camera-cfa-cancel").performClick()
        assertNull(applied)
        compose.onNodeWithContentDescription(context.getString(R.string.camera_preview_tools)).performClick()
        compose.onNodeWithText(context.getString(R.string.camera_cfa_title)).performClick()
        compose.onNodeWithTag("camera-cfa-radio-AUTO", useUnmergedTree=true).assertIsSelected()
    }

    @Test fun previewOverflowOwnsCaptureTasksAndOmitsGlobalDestinations() {
        var targetOpened = false
        var solveOpened = false
        var lp750 = false
        compose.setContent {
            MaterialTheme {
                CameraPreviewTools(
                    connected = true, showPanel = true, showOverlayPanel = true,
                    focusAssistEnabled = false, showCenterMarker = false,
                    lp750Enabled = lp750, lp750Available = true, selectedTarget = null,
                    currentFrameSolveBusy = false, onFitToView = {}, onTogglePanel = {},
                    onToggleOverlay = {}, onToggleFocusAssist = {}, onToggleCenterMarker = {},
                    onToggleLp750 = { lp750 = it }, onSolveCurrentFrame = { solveOpened = true },
                    onChooseTarget = { targetOpened = true }
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.camera_preview_tools)).performClick()
        compose.onNodeWithText(context.getString(R.string.wf_choose_target)).assertIsDisplayed().performClick()
        assertTrue(targetOpened)
        compose.onNodeWithContentDescription(context.getString(R.string.camera_preview_tools)).performClick()
        compose.onNodeWithText(context.getString(R.string.plate_solve_current_frame)).assertIsDisplayed().performClick()
        assertTrue(solveOpened)
        compose.onNodeWithContentDescription(context.getString(R.string.camera_preview_tools)).performClick()
        compose.onNodeWithText(context.getString(R.string.lp750_preview_mapping)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.red_night_mode)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.video_library)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.plate_solve)).assertDoesNotExist()
    }

    @Test fun largeFontCanScrollToTheLastPatternAndExplicitlyResetAndApply() {
        var result: Pair<SerCfaInterpretation, Boolean>? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme { CameraCfaDialog(SerCfaInterpretation.AUTO, true, true, false, { null },
                    onApply={ value, reset -> result=value to reset }, onDismiss={}) }
            }
        }
        compose.onNodeWithTag("camera-cfa-GBRG").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.camera_cfa_reset_apply)).assertIsDisplayed()
        compose.onNodeWithTag("camera-cfa-apply").performClick()
        assertEquals(SerCfaInterpretation.GBRG to true, result)
    }

    @Test fun unappliedDraftSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            CameraCfaDialog(SerCfaInterpretation.AUTO, true, false, false, { null }, onApply={ _, _ -> }, onDismiss={})
        } }
        compose.onNodeWithTag("camera-cfa-GRBG").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("camera-cfa-radio-GRBG", useUnmergedTree=true).assertIsSelected()
    }
}
