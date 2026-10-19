package com.indigo.mobileobservatory.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.stacking.*
import com.indigo.mobileobservatory.ui.components.StackRoiPanel
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Compose component with explicit 585 sensor/budget fixtures, no physical camera claims. */
@RunWith(AndroidJUnit4::class)
class StackRoiPlannerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun context() = StackRoiContext(
        StackSessionKey("585 UI fixture", 3856, 2180, PixelFormat.BAYER_RG16, Roi(0, 0, 3856, 2180),
            1, 1, 10_000.0, 0f, "none", "raw", pixelTraits = requireNotNull(CameraScienceLayouts.fullContainer(PixelFormat.BAYER_RG16))),
        3856, 2180, 128L shl 20, minWidth = 16, minHeight = 16, widthStep = 4, heightStep = 2)

    @Test fun asynchronousApplicationShowsProgressFailureAndRetrySuccess() {
        var current by mutableStateOf(context())
        var application by mutableStateOf(StackRoiApplyState())
        var request: Roi? = null
        compose.setContent { MobileObservatoryTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                StackRoiPanel(current, application = application) { roi ->
                    request = roi
                    application = StackRoiApplyState(StackRoiApplyPhase.APPLYING)
                    true
                }
            }
        } } }
        compose.onNodeWithTag("stack-roi-apply").performScrollTo().performClick()
        compose.onNodeWithTag("stack-roi-apply").assertIsNotEnabled()
        compose.onNodeWithTag("stack-roi-result").assertTextEquals(app.getString(R.string.stack_roi_applying))
        compose.runOnIdle { application = StackRoiApplyState(StackRoiApplyPhase.FAILED, "roi_apply_failed") }
        compose.onNodeWithTag("stack-roi-result").assertTextEquals(app.getString(R.string.stack_roi_failed))
        compose.onNodeWithTag("stack-roi-apply").assertIsEnabled().performClick()
        compose.runOnIdle {
            current = current.copy(key = current.withRoi(requireNotNull(request)))
            application = StackRoiApplyState(StackRoiApplyPhase.APPLIED)
        }
        compose.onNodeWithTag("stack-roi-result").assertTextEquals(app.getString(R.string.stack_roi_applied))
        compose.onNodeWithTag("stack-roi-apply").assertIsNotEnabled()
    }

    @Test fun ratioFrameAndApplyUseTheDisplayedBudgetAndPreserveCfaParity() {
        var current by mutableStateOf(context())
        var applied: Roi? = null
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.setContent { MobileObservatoryTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                StackRoiPanel(current) { roi -> applied = roi; current = current.copy(key = current.withRoi(roi)); true }
            }
        } } }
        val expected = requireNotNull(StackRoiPlanner.maximum(current, StackRoiAspect.SENSOR))
        compose.onNodeWithText(app.getString(R.string.stack_roi_maximum, expected.width, expected.height)).assertExists()
        evidence("585-budget-portrait")
        compose.onNodeWithTag("stack-roi-aspect-SQUARE").performScrollTo().performClick()
        compose.onNodeWithTag("stack-roi-frame").performScrollTo().performTouchInput { click(Offset(1f, 1f)) }
        compose.onNodeWithTag("stack-roi-apply").performScrollTo().performClick()
        compose.runOnIdle {
            val roi = requireNotNull(applied)
            assertEquals(roi.width, roi.height)
            assertEquals(0, roi.x); assertEquals(0, roi.y)
            assertNull(current.issue(roi))
        }
        compose.onNodeWithTag("stack-roi-apply").assertIsNotEnabled()
        // Applying updates the ROI/state; a subsequent frame tap must target the new state.
        compose.onNodeWithTag("stack-roi-frame").performScrollTo().performTouchInput { click(bottomRight - Offset(1f, 1f)) }
        compose.onNodeWithTag("stack-roi-apply").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(applied!!.x > 0 && applied!!.y > 0) }
    }

    @Test fun actualBinAndAlgorithmChangesRecalculateTheFrameAndUnverifiedCfaBlocksIt() {
        var current by mutableStateOf(context())
        compose.setContent { MobileObservatoryTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                StackRoiPanel(current, showBinning = true, onBin = { bin -> current = current.copy(key = current.key.copy(softwareBin = bin)) }) { true }
            }
        } } }
        val first = requireNotNull(StackRoiPlanner.maximum(current, StackRoiAspect.SENSOR))
        compose.onNodeWithText("2×").performScrollTo().performClick()
        val second = requireNotNull(StackRoiPlanner.maximum(current, StackRoiAspect.SENSOR))
        assertTrue(second.width > first.width)
        compose.onNodeWithText(app.getString(R.string.stack_roi_maximum, second.width, second.height)).assertExists()
        compose.runOnIdle { current = current.copy(key = current.key.copy(demosaicAlgorithm = DemosaicAlgorithm.BILINEAR)) }
        val lighter = requireNotNull(StackRoiPlanner.maximum(current, StackRoiAspect.SENSOR))
        assertTrue(lighter.width >= second.width)
        compose.runOnIdle { current = current.copy(key = current.key.copy(hardwareBin = 2)) }
        compose.onNodeWithTag("stack-roi-apply").assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.stack_roi_unavailable)).assertExists()
    }

    @Test fun recordingAndCalibrationLocksKeepTheRangeVisibleWithoutApplyingIt() {
        var current by mutableStateOf(context().copy(blockedReason = "recording_active"))
        var writes = 0
        compose.setContent { MobileObservatoryTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                StackRoiPanel(current) { writes++; true }
            }
        } } }
        compose.onNodeWithTag("stack-roi-apply").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { current = current.copy(blockedReason = "roi_calibration_selected") }
        compose.onNodeWithText(app.getString(R.string.stack_roi_calibration_locked)).assertExists()
        compose.onNodeWithTag("stack-roi-apply").assertIsNotEnabled()
        compose.runOnIdle { current = current.copy(blockedReason = null) }
        compose.onNodeWithTag("stack-roi-apply").performClick()
        compose.runOnIdle { assertEquals(1, writes) }
    }

    private fun evidence(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(app.getExternalFilesDir(null), "roi-budget-evidence/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun landscapeLargeTextAndNightModeKeepTheFrameAndApplyReachable() {
        val current = context()
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MobileObservatoryTheme(redNightMode = true) { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        StackRoiPanel(current) { true }
                    }
                } }
            }
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("stack-roi-aspect-SIXTEEN_NINE").performScrollTo().performClick()
        compose.onNodeWithTag("stack-roi-frame").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("stack-roi-apply").performScrollTo().assertIsDisplayed().assertIsEnabled()
        evidence("585-budget-landscape-large-night")
    }
}
