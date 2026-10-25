package com.indigo.mobileobservatory.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.FrameProcessor
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.guide.*
import com.indigo.mobileobservatory.ui.screens.GuideImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GuideWorkbenchInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun rawBayerPreviewKeepsTheOriginalMonochromeSamples() {
        val pixels = ByteArray(32 * 32) { if (it % 2 == 0) 40 else 180.toByte() }
        val processor = FrameProcessor().apply { rawMonochrome = true; autoStretchEnabled = false }
        val bitmap = processor.frameToBitmap(FrameData(pixels, 32, 32, PixelFormat.BAYER_RG8, 1, 0))
        for (x in 4..20) {
            val value = bitmap.getPixel(x, 8)
            assertEquals(android.graphics.Color.red(value), android.graphics.Color.green(value))
            assertEquals(android.graphics.Color.green(value), android.graphics.Color.blue(value))
            assertEquals(if (x % 2 == 0) 40 else 180, android.graphics.Color.red(value))
        }
    }

    @Test fun selectionUsesSourceCoordinatesAndRejectsTheLetterbox() {
        val frame = frame()
        val selections = mutableListOf<Pair<Float, Float>>()
        compose.setContent { MaterialTheme { GuideImage(frame, emptyList(), { x, y -> selections += x to y }, Modifier.size(320.dp, 200.dp)) } }
        compose.onNodeWithTag("guide-image").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(320f, selections.single().first, 1f)
            assertEquals(240f, selections.single().second, 1f)
        }
        compose.onNodeWithTag("guide-image").performTouchInput { click(Offset(1f, height / 2f)) }
        compose.runOnIdle { assertEquals(1, selections.size) }
    }

    @Test fun starMarkersAreClippedToTheImageAndMoveWithZoom() {
        val frame = frame()
        compose.setContent { MaterialTheme { GuideImage(frame, emptyList(), { _, _ -> }, Modifier.size(320.dp, 200.dp)) } }
        val image = compose.onNodeWithTag("guide-image")
        val before = image.captureToImage().toPixelMap()
        val yellowBefore = yellowCenter(before)
        image.performTouchInput {
            val cy = height / 2f
            down(0, Offset(width * .4f, cy)); down(1, Offset(width * .6f, cy))
            repeat(5) { i ->
                advanceEventTime(20)
                moveTo(0, Offset(width * (.4f - (i + 1) * .025f), cy))
                moveTo(1, Offset(width * (.6f + (i + 1) * .025f), cy))
            }
            up(0); up(1)
        }
        val after = image.captureToImage().toPixelMap()
        val yellowAfter = yellowCenter(after)
        assertTrue("The primary marker must follow the image transform", yellowAfter < yellowBefore - before.width * .05f)
        assertEquals(0f, before[1, before.height / 4].red, .01f)
    }

    private fun frame(): GuideDisplayFrame {
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        val star = GuideStar(240f, 240f, 20f)
        return GuideDisplayFrame(bitmap, 640, 480, listOf(star), star, null)
    }

    private fun yellowCenter(pixels: androidx.compose.ui.graphics.PixelMap): Float {
        var weightedX = 0f; var count = 0
        // Sample the vertical primary cross, clear of its horizontal arm and square.
        for (y in pixels.height / 8 until pixels.height / 4) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > .4f && color.green > .3f && color.blue < .3f) { weightedX += x; count++ }
        }
        assertTrue("Primary cross is visible", count > 0)
        return weightedX / count
    }
}
