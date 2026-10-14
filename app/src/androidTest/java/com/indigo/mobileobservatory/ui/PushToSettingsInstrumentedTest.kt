package com.indigo.mobileobservatory.ui

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.PhoneCameraCapability
import com.indigo.mobileobservatory.camera.PhoneLensRole
import com.indigo.mobileobservatory.ui.screens.CaptureSettingsCard
import com.indigo.mobileobservatory.ui.screens.PushToAdaptiveLayout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PushToSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exposureDragCommitsOnceAfterContinuousChanges() {
        val changes = AtomicInteger()
        val commits = AtomicInteger()
        var exposure by mutableFloatStateOf(1f)
        val camera = camera()
        compose.setContent {
            MaterialTheme {
                CaptureSettingsCard(
                    cameras = listOf(camera),
                    selectedCamera = camera,
                    cameraMenuOpen = false,
                    onCameraMenuChange = {},
                    onCameraSelected = {},
                    exposureSeconds = exposure,
                    onExposureChanged = { exposure = it; changes.incrementAndGet() },
                    onContinuousChangeFinished = { commits.incrementAndGet() },
                    iso = 800f,
                    onIsoChanged = {},
                    autoIso = true,
                    onAutoIsoChanged = {},
                    preferRaw = true,
                    onPreferRawChanged = {},
                    burstFrameCount = 1f,
                    onBurstFrameCountChanged = {},
                    expanded = true,
                    collapsible = false,
                    onExpandedChanged = {}
                )
            }
        }

        compose.onNodeWithTag("push-exposure-slider").performTouchInput {
            down(centerLeft)
            repeat(12) { step ->
                advanceEventTime(50)
                moveTo(centerLeft + (centerRight - centerLeft) * ((step + 1) / 12f))
            }
            up()
        }
        compose.waitForIdle()

        assertTrue("Drag must update the in-memory value continuously", changes.get() > 1)
        assertEquals("One drag must produce one persistence boundary", 1, commits.get())
    }

    @Test fun lensMenuTogglesClosesOnBackAndClosesAfterSelection() {
        val first = camera("main-a", 22.1f)
        val second = camera("main-b", 22.4f)
        var open = false
        var selected by mutableStateOf(first)
        val selectedId = AtomicReference<String?>(null)
        compose.setContent {
            MaterialTheme {
                CaptureSettingsCard(
                    cameras = listOf(first, second), selectedCamera = selected,
                    cameraMenuOpen = open, onCameraMenuChange = { open = it },
                    onCameraSelected = { selected = it; selectedId.set(it.cameraId); open = false },
                    exposureSeconds = 1f, onExposureChanged = {}, iso = 800f, onIsoChanged = {},
                    autoIso = true, onAutoIsoChanged = {}, preferRaw = true, onPreferRawChanged = {},
                    burstFrameCount = 1f, onBurstFrameCountChanged = {}, expanded = true,
                    collapsible = false, onExpandedChanged = {}
                )
            }
        }
        val main = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.push_to_lens_main)
        val firstLabel = "$main · 22.1 mm · RAW"
        val secondLabel = "$main · 22.4 mm · RAW"

        compose.onNodeWithTag("push-camera-menu-anchor").performClick()
        compose.onAllNodesWithText(firstLabel).assertCountEquals(2)
        compose.onNodeWithText(secondLabel).assertExists()
        compose.onNodeWithTag("push-camera-menu-anchor").performClick()
        compose.onNodeWithText(secondLabel).assertDoesNotExist()

        compose.onNodeWithTag("push-camera-menu-anchor").performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText(secondLabel).assertDoesNotExist()

        compose.onNodeWithTag("push-camera-menu-anchor").performClick()
        compose.onNodeWithText(secondLabel).performClick()
        compose.onNodeWithText(secondLabel).assertExists()
        compose.onNodeWithText(firstLabel).assertDoesNotExist()
        assertEquals("main-b", selectedId.get())
    }

    @Test fun landscapeUsesTwoColumnPushToLayout() {
        compose.setContent {
            PushToAdaptiveLayout(
                modifier = Modifier.requiredSize(800.dp, 400.dp),
                settingsInMain = true,
                workflow = { Box(Modifier.testTag("workflow-slot")) },
                main = { modifier, _ -> Box(modifier.testTag("main-slot")) },
                settings = { Box(Modifier.testTag("settings-slot")) },
                actions = { Box(Modifier.testTag("actions-slot")) }
            )
        }

        compose.onNodeWithTag("push-layout-wide").assertExists()
        compose.onNodeWithTag("push-layout-standard").assertDoesNotExist()
        compose.onNodeWithTag("main-slot").assertExists()
        compose.onNodeWithTag("settings-slot").assertExists()
        compose.onNodeWithTag("actions-slot").assertExists()
    }

    @Test fun portraitKeepsSingleColumnPushToLayout() {
        compose.setContent {
            PushToAdaptiveLayout(
                modifier = Modifier.requiredSize(400.dp, 800.dp),
                settingsInMain = false,
                workflow = {},
                main = { modifier, _ -> Box(modifier) },
                settings = {},
                actions = {}
            )
        }

        compose.onNodeWithTag("push-layout-standard").assertExists()
        compose.onNodeWithTag("push-layout-wide").assertDoesNotExist()
    }

    private fun camera(cameraId: String = "main", equivalentFocalLengthMm: Float = 23.4f) = PhoneCameraCapability(
        cameraId = cameraId,
        facing = CameraCharacteristics.LENS_FACING_BACK,
        lensRole = PhoneLensRole.MAIN,
        equivalentFocalLengthMm = equivalentFocalLengthMm,
        supportsRaw = true,
        supportsManualSensor = true,
        exposureTimeRangeNs = Range(10_000_000L, 2_000_000_000L),
        isoRange = Range(50, 3200),
        focalLengthMm = 5.4f,
        sensorWidthMm = 8f,
        sensorHeightMm = 6f,
        activeArraySize = null,
        preCorrectionActiveArraySize = null,
        yuvOutputSizes = emptyList(),
        rawOutputSizes = emptyList(),
        maxDigitalZoom = 1f,
        noiseReductionModes = intArrayOf(),
        edgeModes = intArrayOf(),
        oisAvailable = false
    )
}
