package com.indigo.mobileobservatory.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.ui.screens.PointingOpticsConfigurationSheet
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The native-map editor uses the same actual main/guide geometry and persistent optics keys. */
class PointingOpticsSheetInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    private var saved: Map<String, *> = emptyMap<String, Any>()
    private fun relevant(key: String) = key.startsWith("star_map_") || key == StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM

    @Before fun seedIndependentTrainChoices() {
        saved = prefs.all.filterKeys(::relevant)
        val editor = prefs.edit()
        prefs.all.keys.filter(::relevant).forEach { editor.remove(it) }
        StarMapOpticsPrefs.snapshot(
            OpticsTrainConfig(OpticsTrainId.PRIMARY, FovInstrumentMode.SENSOR),
            OpticsTrainConfig(OpticsTrainId.SECONDARY, FovInstrumentMode.SENSOR),
            OpticsTrainId.PRIMARY, true).strings.forEach { (key, value) -> editor.putString(key, value) }
        editor.putFloat(StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM, 250f).commit()
    }

    @After fun restoreOpticsPreferences() {
        val editor = prefs.edit()
        prefs.all.keys.filter(::relevant).forEach { editor.remove(it) }
        saved.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> Unit
        } }
        editor.commit()
    }

    @Test fun reusedSheetSeparatesConnectedCamerasAndPersistsConfirmedSetupWithLocalInvalidation() {
        val invalidated = mutableListOf<OpticsTrainId>()
        compose.setContent {
            MaterialTheme {
                PointingOpticsConfigurationSheet(
                    ConnectedOpticsGeometry(3.76f, 3008, 3008, deviceId = "main"),
                    ConnectedOpticsGeometry(2.9f, 1920, 1080, deviceId = "guide"),
                    onOpticalPathInvalidated = { train, _ -> invalidated += train }, onDismiss = {})
            }
        }
        fun assertField(pixel: Float, width: Int, height: Int) {
            val field = OpticsEquipment.computeSensor(500.0, SensorSpec("test", "test", pixel.toDouble(), width, height))
            compose.onNodeWithText(context.getString(R.string.fov_summary_sensor, field.rectWidthDeg, field.rectHeightDeg))
                .performScrollTo().assertIsDisplayed()
        }
        assertField(3.76f, 3008, 3008)
        compose.onAllNodesWithText("3008").assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.star_map_train_secondary)).performScrollTo().performClick()
        assertField(2.9f, 1920, 1080)
        compose.onAllNodesWithText("3008").assertCountEquals(0)
        compose.onNodeWithText("1920").assertExists()
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.focal_length_mm)))
            .performScrollTo().performTextReplacement("")
        compose.onNodeWithText(context.getString(R.string.wf_optics_use_setup)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.fov_summary_incomplete)).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.focal_length_mm)))
            .performScrollTo().performTextReplacement("500")
        compose.onNodeWithText(context.getString(R.string.wf_optics_use_setup)).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(prefs.getBoolean(PointingOpticsConfiguration.confirmationKey(OpticsTrainId.SECONDARY), false))
            assertFalse(prefs.getBoolean(PointingOpticsConfiguration.confirmationKey(OpticsTrainId.PRIMARY), false))
            assertEquals(250f, prefs.getFloat(StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM, 0f), 0f)
            assertTrue(invalidated.isEmpty())
        }
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.focal_length_mm)))
            .performScrollTo().performTextReplacement("600")
        compose.runOnIdle {
            assertEquals(listOf(OpticsTrainId.SECONDARY), invalidated)
            assertEquals(600f, prefs.getFloat(StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM, 0f), 0f)
            assertFalse(prefs.getBoolean(PointingOpticsConfiguration.confirmationKey(OpticsTrainId.SECONDARY), true))
        }
        compose.onNodeWithText(context.getString(R.string.star_map_train_primary)).performScrollTo().performClick()
        assertField(3.76f, 3008, 3008)
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.focal_length_mm))).assertTextContains("500")
        compose.runOnIdle {
            assertEquals(listOf(OpticsTrainId.SECONDARY), invalidated)
            assertFalse(prefs.getBoolean(PointingOpticsConfiguration.confirmationKey(OpticsTrainId.PRIMARY), false))
        }
    }

    @Test fun taskTerminalIsConfirmedInPrimaryWithoutExtraTrainOrModeChoices() {
        val visible = mutableStateOf(true)
        var confirmed: PointingOpticsConfiguration? = null
        val invalidated = mutableListOf<OpticsTrainId>()
        compose.setContent {
            MaterialTheme {
                if (visible.value) PointingOpticsConfigurationSheet(null, null,
                    onOpticalPathInvalidated = { train, _ -> invalidated += train }, onDismiss = { visible.value = false },
                    initialTrain = OpticsTrainId.PRIMARY, terminal = PointingOpticsEndpoint.EYEPIECE,
                    restrictToTrain = true, onConfirmed = { confirmed = it; visible.value = false })
            }
        }
        compose.onNodeWithText(context.getString(R.string.star_map_train_secondary)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.wf_optics_use_setup)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.fov_simulator_title)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(FovInstrumentMode.EYEPIECE, confirmed!!.primary.mode)
            assertEquals(FovInstrumentMode.SENSOR, confirmed!!.secondary.mode)
            assertTrue(OpticsTrainId.PRIMARY in confirmed!!.confirmedTrains)
            assertFalse(OpticsTrainId.SECONDARY in confirmed!!.confirmedTrains)
            assertEquals(listOf(OpticsTrainId.PRIMARY), invalidated)
            assertEquals("EYEPIECE", prefs.getString("star_map_fov_mode", null))
            assertEquals(250f, prefs.getFloat(StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM, 0f), 0f)
        }
    }
}
