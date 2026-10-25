package com.indigo.mobileobservatory.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.recording.MediaCategory
import com.indigo.mobileobservatory.ui.screens.PlateSolveScreen
import com.indigo.mobileobservatory.ui.screens.PlayerScreen
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSolveShareInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    @Before fun fixtures() { root = File(context.cacheDir, "media-selection-${UUID.randomUUID()}").apply { check(mkdirs()) } }
    @After fun cleanUp() { root.deleteRecursively() }

    private fun image(path: String): File = File(root, path).apply {
        parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun appImageSelectionCancelAndRestorationPreserveCurrentFile() {
        val first = image("captures/JPG/first.png")
        val second = image("captures/JPG/second.png")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            PlateSolveScreen(initialFile = first, recordingsDir = File(root, "recordings"),
                capturesDir = File(root, "captures"), onBack = {})
        } }
        compose.onNodeWithTag("plate-choose-library").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(second.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.onNodeWithTag("plate-selected-image").assertTextEquals(first.name)
        compose.onNodeWithTag("plate-choose-library").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(second.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(second.name).performClick()
        compose.onNodeWithTag("plate-selected-image").assertTextEquals(second.name)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("plate-selected-image").assertTextEquals(second.name)
    }

    @Test fun albumExposesSingleFileShareWithoutBatchSelection() {
        val image = image("captures/JPG/photo.png")
        compose.setContent { MaterialTheme {
            PlayerScreen(File(root, "recordings"), File(root, "captures"), initialCategory = MediaCategory.PHOTOS, onBack = {})
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("media-share-${image.name}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media-share-${image.name}").assertIsDisplayed().assertHasClickAction()
    }

    @Test fun viewerOffersPreviewAndOriginalSharingAndReturnsFromSolveToSameImage() {
        val image = image("captures/JPG/photo.png")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            PlayerScreen(File(root, "recordings"), File(root, "captures"), initialFile = image, onBack = {})
        } }
        compose.onNodeWithTag("media-image-share").performClick()
        compose.onNodeWithText(context.getString(R.string.media_share_preview)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.media_share_original)).assertIsDisplayed()
        // A normal Back closes the share menu before leaving the image.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription(context.getString(R.string.plate_solve)).performClick()
        compose.onNodeWithTag("plate-selected-image").assertTextEquals(image.name)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("plate-selected-image").assertTextEquals(image.name)
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.onNodeWithTag("media-image-share").assertIsDisplayed()
        compose.onNodeWithText(image.name).assertIsDisplayed()
    }
}
