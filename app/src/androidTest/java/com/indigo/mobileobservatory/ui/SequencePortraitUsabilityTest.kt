package com.indigo.mobileobservatory.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.sequence.SequenceRuntime
import com.indigo.mobileobservatory.sequence.VirtualSequenceHardware
import com.indigo.mobileobservatory.sequence.addSequenceNode
import com.indigo.mobileobservatory.sequence.childItems
import com.indigo.mobileobservatory.sequence.emptyAdvancedSequence
import com.indigo.mobileobservatory.sequence.findSequenceNode
import com.indigo.mobileobservatory.sequence.setSequenceField
import com.indigo.mobileobservatory.sequence.toJson
import com.indigo.mobileobservatory.ui.screens.SequenceAdvancedEditor
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SequencePortraitUsabilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun deeplyNestedSequenceCanBeEditedAndReachedOnPortraitScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = emptyAdvancedSequence("Portrait nesting")
        var parent = root.childItems().first { it.className == "StartAreaContainer" }
        repeat(6) { index ->
            assertTrue(addSequenceNode(root, checkNotNull(parent.id), "SequentialContainer"))
            parent = parent.childItems().last()
            assertTrue(setSequenceField(root, checkNotNull(parent.id), "Name", "Nested level ${index + 1}"))
        }
        assertTrue(addSequenceNode(root, checkNotNull(parent.id), "Annotation"))
        val deepestId = checkNotNull(parent.id)
        val runtime = SequenceRuntime(
            templatesDir = File(context.cacheDir, "portrait-sequence-templates"),
            sessionsDir = File(context.cacheDir, "portrait-sequence-sessions"),
            scope = scope,
            hardware = VirtualSequenceHardware()
        )
        runtime.importJson("portrait.json", root.toJson())

        val configuration = Configuration(context.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = 360
            screenHeightDp = 640
            setLocale(Locale.ENGLISH)
        }
        val localizedContext = context.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration
            ) {
                MaterialTheme {
                    Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                        SequenceAdvancedEditor(
                            runtime = runtime,
                            enabled = true,
                            hardware = VirtualSequenceHardware.SNAPSHOT
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("sequence_collapse_all").assertIsDisplayed().performClick()
        assertTrue(
            compose.onAllNodesWithTag("sequence_add_item_$deepestId")
                .fetchSemanticsNodes().isEmpty()
        )
        compose.onNodeWithTag("sequence_expand_all").assertIsDisplayed().performClick()

        compose.onNodeWithTag("sequence_add_item_$deepestId")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("sequence_catalog_MessageBox")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("sequence_catalog_MessageBox")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle {
            val deepest = checkNotNull(findSequenceNode(checkNotNull(runtime.document.value), deepestId))
            assertEquals(listOf("Annotation", "MessageBox"), deepest.childItems().map { it.className })
        }
        compose.onNodeWithText("End").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun shortNumericFieldsShareARow() {
        val exposureId = showTakeExposureEditor("compact")
        compose.onNodeWithTag("sequence_node_$exposureId")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("sequence_field_${exposureId}_ExposureTime").performScrollTo()
        val exposureBounds = compose
            .onNodeWithTag("sequence_field_${exposureId}_ExposureTime")
            .fetchSemanticsNode().boundsInRoot
        val gainBounds = compose
            .onNodeWithTag("sequence_field_${exposureId}_Gain")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("numeric fields should share a row", kotlin.math.abs(exposureBounds.top - gainBounds.top) < 1f)
    }

    @Test
    fun tappingAnOpenInstructionAgainCollapsesItsFields() {
        val exposureId = showTakeExposureEditor("toggle")
        val row = compose.onNodeWithTag("sequence_node_$exposureId")
        row.performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("sequence_field_${exposureId}_ExposureTime").fetchSemanticsNode()
        row.performClick()
        assertTrue(
            compose.onAllNodesWithTag("sequence_field_${exposureId}_ExposureTime")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    private fun showTakeExposureEditor(testName: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = emptyAdvancedSequence("Compact fields")
        val start = root.childItems().first { it.className == "StartAreaContainer" }
        assertTrue(addSequenceNode(root, checkNotNull(start.id), "TakeExposure"))
        val exposureId = checkNotNull(start.childItems().single().id)
        val runtime = SequenceRuntime(
            templatesDir = File(context.cacheDir, "$testName-sequence-templates"),
            sessionsDir = File(context.cacheDir, "$testName-sequence-sessions"),
            scope = scope,
            hardware = VirtualSequenceHardware()
        )
        runtime.importJson("$testName.json", root.toJson())

        val configuration = Configuration(context.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = 360
            screenHeightDp = 640
            setLocale(Locale.ENGLISH)
        }
        val localizedContext = context.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration
            ) {
                MaterialTheme {
                    Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                        SequenceAdvancedEditor(
                            runtime = runtime,
                            enabled = true,
                            hardware = VirtualSequenceHardware.SNAPSHOT
                        )
                    }
                }
            }
        }
        return exposureId
    }
}
