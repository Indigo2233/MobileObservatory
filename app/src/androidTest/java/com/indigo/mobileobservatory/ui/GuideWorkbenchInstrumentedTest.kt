package com.indigo.mobileobservatory.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.FrameProcessor
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.guide.*
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import com.indigo.mobileobservatory.ui.screens.GuideImage
import com.indigo.mobileobservatory.ui.screens.GuideStarThumbnail
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class GuideWorkbenchInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun hdrRgbFramesRemainMonochromeInTheGuidePreview() {
        for (format in listOf(PixelFormat.RGB24, PixelFormat.RGB48)) {
            val bytes = ByteArray(32 * 32 * format.bytesPerPixel)
            for (i in 0 until 32 * 32) {
                val channels = intArrayOf(20, 150, 50)
                for (channel in 0..2) {
                    val offset = i * format.bytesPerPixel + channel * if (format == PixelFormat.RGB48) 2 else 1
                    if (format == PixelFormat.RGB48) bytes[offset + 1] = channels[channel].toByte()
                    else bytes[offset] = channels[channel].toByte()
                }
            }
            val processor = FrameProcessor().apply { rawMonochrome = true; autoStretchEnabled = false }
            val pixel = processor.frameToBitmap(FrameData(bytes, 32, 32, format, 1, 0)).getPixel(16, 16)
            assertEquals("Guide RGB channels differ in $format", android.graphics.Color.red(pixel), android.graphics.Color.green(pixel))
            assertEquals(android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
        }
    }

    @Test fun bayerGuidePreviewCombinesCellsWhilePreservingTheSensorFrame() {
        val pixels = ByteArray(32 * 32) { if (it % 2 == 0) 40 else 180.toByte() }
        val original = pixels.clone()
        val processor = FrameProcessor().apply { rawMonochrome = true; autoStretchEnabled = false }
        val frame = FrameData(pixels, 32, 32, PixelFormat.BAYER_RG8, 1, 0)
        val bitmap = processor.frameToBitmap(frame)
        assertEquals(32, bitmap.width); assertEquals(32, bitmap.height)
        for (x in 4..20) {
            val value = bitmap.getPixel(x, 8)
            assertEquals(android.graphics.Color.red(value), android.graphics.Color.green(value))
            assertEquals(android.graphics.Color.green(value), android.graphics.Color.blue(value))
            assertEquals(110, android.graphics.Color.red(value))
        }
        assertArrayEquals(original, frame.data)
        assertEquals(PixelFormat.BAYER_RG8, frame.pixelFormat)
    }

    @Test fun guideMonoFramesKeepTheirIndividualSamples() {
        val pixels = ByteArray(32 * 32) { if (it % 2 == 0) 40 else 180.toByte() }
        val processor = FrameProcessor().apply { rawMonochrome = true; autoStretchEnabled = false }
        val bitmap = processor.frameToBitmap(FrameData(pixels, 32, 32, PixelFormat.MONO8, 1, 0))
        for (x in 4..20) assertEquals(if (x % 2 == 0) 40 else 180, android.graphics.Color.red(bitmap.getPixel(x, 8)))
    }

    @Test fun highBitCfaPreviewUsesTheCameraAlignmentAndByteOrder() {
        for (order in listOf(java.nio.ByteOrder.LITTLE_ENDIAN, java.nio.ByteOrder.BIG_ENDIAN)) {
            for (alignment in listOf(SampleAlignment.LEFT, SampleAlignment.RIGHT)) {
                val source = java.nio.ByteBuffer.allocate(32 * 32 * 2).order(order)
                repeat(32 * 32) { index ->
                    val value = if (index % 2 == 0) 1000 else 3000
                    source.putShort((if (alignment == SampleAlignment.LEFT) value shl 4 else value).toShort())
                }
                val original = source.array().clone()
                val processor = FrameProcessor().apply { rawMonochrome = true; autoStretchEnabled = false }
                val bitmap = processor.frameToBitmap(FrameData(source.array(), 32, 32, PixelFormat.BAYER_RG12, 1, 0),
                    PixelTraits(byteOrder = order, alignment = alignment, validBits = 12))
                for (x in 4..20) assertEquals(124, android.graphics.Color.red(bitmap.getPixel(x, 8)))
                assertArrayEquals(original, source.array())
            }
        }
    }

    @Test fun autoStretchHistogramUsesTheComposedCfaLuminance() {
        val pixels = ByteArray(32 * 32) { if (it % 2 == 0) 40 else 180.toByte() }
        val processor = FrameProcessor().apply { rawMonochrome = true }
        processor.frameToBitmap(FrameData(pixels, 32, 32, PixelFormat.BAYER_RG8, 1, 0))
        val histogram = requireNotNull(processor.histogram.value)
        assertEquals(32 * 32, histogram.bins[110])
        assertEquals(0, histogram.bins[40]); assertEquals(0, histogram.bins[180])
    }

    @Test fun starThumbnailDisplaysCfaLuminanceAndRetainsItsRawProfile() {
        val raw = List(25 * 25) { if ((it / 25 + it % 25) % 2 == 0) 40f else 180f }
        val profile = GuideStarProfile(25, 25, raw, 2f, 100f, 180f, 0f, emptyList(), emptyList(), isBayer = true)
        compose.setContent { MaterialTheme { GuideStarThumbnail(profile, Modifier.size(64.dp).testTag("cfa-thumbnail")) } }
        val pixels = compose.onNodeWithTag("cfa-thumbnail").captureToImage().toPixelMap()
        for (y in 1 until pixels.height - 1) for (x in 1 until pixels.width - 1) {
            assertEquals(1f, pixels[x, y].red, .01f)
            assertEquals(pixels[x, y].red, pixels[x, y].green, .001f)
        }
        assertEquals(raw, profile.pixels)
        assertEquals(180f, profile.peak, 0f)
    }

    @Test fun tappingTheStarViewSwitchesBetweenRawPixelsAndTheInterpolatedView() {
        // PHD2 switches the star view between wxIMAGE_QUALITY_NEAREST and
        // wxIMAGE_QUALITY_HIGH on a click (src/star_profile.cpp, ProfileWindow::OnLClick).
        val pixels = MutableList(25 * 25) { 0f }
        pixels[12 * 25 + 12] = 200f
        val profile = GuideStarProfile(25, 25, pixels, 2f, 100f, 200f, 0f, emptyList(), emptyList())
        var raw by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            GuideStarThumbnail(profile, Modifier.size(64.dp).testTag("star-view"), raw) { raw = !raw }
        } }
        val smooth = compose.onNodeWithTag("star-view").captureToImage().toPixelMap()
        compose.onNodeWithTag("star-view").performTouchInput { click(center) }
        compose.runOnIdle { assertTrue("The click must switch the view", raw) }
        val rawPixels = compose.onNodeWithTag("star-view").captureToImage().toPixelMap()
        var differences = 0
        for (y in 0 until smooth.height) for (x in 0 until smooth.width)
            if (abs(smooth[x, y].red - rawPixels[x, y].red) > .01f) differences++
        assertTrue("The two views must differ", differences > 0)
    }

    @Test fun selectionUsesSourceCoordinatesAndRejectsTheLetterbox() {
        val frame = frame()
        val selections = mutableListOf<Pair<Float, Float>>()
        compose.setContent { MaterialTheme { GuideImage(frame, emptyList(), { x, y -> selections += x to y }, Modifier.size(320.dp, 200.dp)) } }
        compose.onNodeWithTag("guide-image").performTouchInput { click(center) }
        // A single tap is delivered after the double-tap recognition window.
        compose.mainClock.advanceTimeBy(500)
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

    @Test fun fingerOffsetOnFullResolutionPreviewSelectsTheNearbyStar() {
        val star = GuideStar(1920f, 1080f, 20f)
        val frame = frame().copy(width = 3840, height = 2160, stars = listOf(star), primary = star)
        val selections = mutableListOf<Pair<Float, Float>>()
        compose.setContent { MaterialTheme {
            GuideImage(frame, emptyList(), { x, y -> selections += x to y }, Modifier.size(320.dp, 180.dp))
        } }
        compose.onNodeWithTag("guide-image").performTouchInput { click(center + Offset(width * .02f, 0f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(listOf(1920f to 1080f), selections) }
    }

    @Test fun theRegionOfInterestStaysOnTheLockedPositionWhileTheStarMoves() {
        // PHD2 keeps the lock-point crosshair and the secondary markers on the locked
        // positions (src/guider.cpp, Guider::PaintHelper), so a drifting star leaves the
        // marker and the offset becomes visible.
        val primary = GuideStar(160f, 120f, 20f)
        val secondary = GuideStar(320f, 120f, 15f)
        val references = listOf(primary, secondary)
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        // The tracked star has drifted away from its locked reference position.
        val movedPrimary = GuideStar(160f + 40f, 120f, 20f)
        val frame = GuideDisplayFrame(bitmap, 640, 480, listOf(movedPrimary, secondary), movedPrimary, null)
        compose.setContent { MaterialTheme {
            GuideImage(frame, references, { _, _ -> }, Modifier.size(320.dp, 200.dp))
        } }
        val pixels = compose.onNodeWithTag("guide-image").captureToImage().toPixelMap()
        val markers = greenMarkers(pixels)
        assertTrue("Both markers must be drawn", markers.size >= 2)
        val movedX = imageX(movedPrimary.x, 640, 480, pixels.width, pixels.height)
        val referenceX = imageX(primary.x, 640, 480, pixels.width, pixels.height)
        val secondaryX = imageX(secondary.x, 640, 480, pixels.width, pixels.height)
        assertTrue("The region of interest must stay on the locked position",
            markers.any { kotlin.math.abs(it - referenceX) < 6f })
        assertTrue("The secondary marker must stay at its reference position",
            markers.any { kotlin.math.abs(it - secondaryX) < 6f })
        assertTrue("The drifted star must no longer carry a marker",
            markers.none { kotlin.math.abs(it - movedX) < 6f })
    }

    private fun frame(): GuideDisplayFrame {
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        val star = GuideStar(240f, 240f, 20f)
        return GuideDisplayFrame(bitmap, 640, 480, listOf(star), star, null)
    }

    /** Screen x of an image coordinate under the letterboxed preview transform. */
    private fun imageX(imageX: Float, imageWidth: Int, imageHeight: Int, canvasWidth: Int, canvasHeight: Int): Float {
        val scale = minOf(canvasWidth.toFloat() / imageWidth, canvasHeight.toFloat() / imageHeight)
        return (canvasWidth - imageWidth * scale) / 2f + imageX * scale
    }

    /** Horizontal centres of the green star markers in the captured preview. The yellow
     * crosshair overlaps the marker row, so the whole image is scanned for green pixels. */
    private fun greenMarkers(pixels: androidx.compose.ui.graphics.PixelMap): List<Float> {
        val points = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.green > .6f && color.green > color.red * 1.4f && color.green > color.blue * 1.2f)
                points.add(x to y)
        }
        // The circles are hollow, so group the green pixels into rings and take the
        // centre of each bounding box.
        val groups = mutableListOf<MutableList<Pair<Int, Int>>>()
        for (point in points) {
            val group = groups.firstOrNull { candidate -> candidate.any { abs(it.first - point.first) < 40 && abs(it.second - point.second) < 40 } }
            if (group != null) group.add(point) else groups.add(mutableListOf(point))
        }
        return groups.map { group -> group.map { it.first }.average().toFloat() }
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
