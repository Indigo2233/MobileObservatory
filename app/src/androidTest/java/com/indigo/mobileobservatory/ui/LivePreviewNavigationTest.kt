package com.indigo.mobileobservatory.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indigo.mobileobservatory.ui.components.LivePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LivePreviewNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backFromZoomReturnsToNormalBeforeLeavingTheWorkspace() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        var rootBackCount = 0
        compose.setContent {
            MaterialTheme {
                BackHandler { rootBackCount++ }
                LivePreview(bitmap, modifier = Modifier.testTag("preview"))
            }
        }
        val normal = zoomDescription()
        zoomIn()
        assertNotEquals(normal, zoomDescription())
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(normal, zoomDescription())
        assertEquals(0, rootBackCount)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, rootBackCount)
    }

    @Test
    fun zoomSurvivesChildNavigationAndSavedStateRestoration() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        var showPreview by mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                val screens = rememberSaveableStateHolder()
                if (showPreview) screens.SaveableStateProvider("capture") {
                    LivePreview(bitmap, modifier = Modifier.testTag("preview"))
                }
            }
        }
        zoomIn()
        val zoomed = zoomDescription()
        compose.runOnIdle { showPreview = false }
        compose.waitForIdle()
        compose.runOnIdle { showPreview = true }
        assertEquals(zoomed, zoomDescription())
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(zoomed, zoomDescription())
    }

    @Test
    fun changedImageGeometryResetsTheViewport() {
        var bitmap by mutableStateOf(Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888))
        compose.setContent { MaterialTheme { LivePreview(bitmap, modifier = Modifier.testTag("preview")) } }
        val normal = zoomDescription()
        zoomIn()
        assertNotEquals(normal, zoomDescription())
        compose.runOnIdle { bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888) }
        assertEquals(normal, zoomDescription())
    }

    private fun zoomDescription(): String = compose.onNodeWithTag("preview").fetchSemanticsNode()
        .config[SemanticsProperties.StateDescription]

    private fun zoomIn() {
        compose.onNodeWithTag("preview").performTouchInput {
            val y = height / 2f
            down(0, Offset(width * .4f, y))
            down(1, Offset(width * .6f, y))
            repeat(5) { step ->
                advanceEventTime(20)
                moveTo(0, Offset(width * (.4f - (step + 1) * .04f), y))
                moveTo(1, Offset(width * (.6f + (step + 1) * .04f), y))
            }
            up(0)
            up(1)
        }
    }
}
