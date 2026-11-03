package com.indigo.mobileobservatory.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indigo.mobileobservatory.ui.components.FullscreenImageViewer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The full-screen picture viewer owns the pinch zoom, the rotation and the back path. */
@RunWith(AndroidJUnit4::class)
class FullscreenImageViewerInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()

    @Test fun theViewerShowsThePictureAndTheSystemBackClosesIt() {
        val picture = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        var dismissed = false
        try {
            compose.setContent { MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    FullscreenImageViewer(bitmap = picture, onDismiss = { dismissed = true },
                        modifier = Modifier.fillMaxSize())
                }
            } }
            compose.onNodeWithTag("fullscreen-image-viewer").assertIsDisplayed()
            compose.onNodeWithTag("fullscreen-image-surface").assertIsDisplayed()
            compose.onNodeWithTag("fullscreen-image-rotate").assertIsDisplayed()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertTrue("The system back button must close the full-screen viewer", dismissed)
        } finally { picture.recycle() }
    }

    @Test fun thePictureTurnsAndScalesWithTheGestures() {
        val picture = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        try {
            compose.setContent { MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    FullscreenImageViewer(bitmap = picture, onDismiss = { },
                        modifier = Modifier.fillMaxSize())
                }
            } }
            compose.waitForIdle()
            val fitted = state()
            assertTrue("The viewer starts fitted", fitted.contains("scale=1000"))
            assertTrue("The viewer starts unrotated", fitted.contains("rotation=0"))

            compose.onNodeWithTag("fullscreen-image-rotate").performClick()
            compose.waitForIdle()
            val turned = state()
            assertTrue("The rotation button must turn the picture", turned.contains("rotation=90"))

            // A drag must move the picture.
            compose.onNodeWithTag("fullscreen-image-surface").performTouchInput {
                swipe(start = center + Offset(0f, -200f), end = center + Offset(0f, -100f),
                    durationMillis = 200)
            }
            compose.waitForIdle()
            val dragged = state()
            assertTrue("A drag must move the picture", dragged != turned)

            // A double tap returns to the fitted picture.
            compose.onNodeWithTag("fullscreen-image-surface").performTouchInput { doubleClick(center) }
            compose.waitForIdle()
            val reset = state()
            assertTrue("A double tap must reset the picture", reset.contains("scale=1000"))
            assertTrue("A double tap must reset the rotation", reset.contains("rotation=0"))
        } finally { picture.recycle() }
    }

    private fun state(): String =
        compose.onNodeWithTag("fullscreen-image-surface")
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
            .toString()
}
