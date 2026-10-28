package com.indigo.mobileobservatory.ui

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.FrameProcessor
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.CameraPreviewSettings
import com.indigo.mobileobservatory.media.CameraImagePreview
import com.indigo.mobileobservatory.media.MediaImageRegion
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import com.indigo.mobileobservatory.recording.FITSWriter
import com.indigo.mobileobservatory.ui.screens.PolarWorkflowPreview
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min

class PolarPreviewRenderingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()

    @Test fun monochromeAlignmentDisplayPreservesRawBayerFitsAndNativeCrop() {
        val file = sampleFile()
        val data = ByteArray(192 * 128) { i ->
            val x = i % 192; val y = i / 192
            (if (x % 2 == 0 && y % 2 == 0) 40 else if (x % 2 == 1 && y % 2 == 1) 20 else 170).toByte()
        }
        try {
            FITSWriter().write(file, FrameData(data, 192, 128, PixelFormat.BAYER_RG8, 1, 0), 3f, 100f)
            val original = file.readBytes()
            val colour = CameraImagePreview.prepare(file, CameraPreviewSettings(autoStretch = false))
            val mono = CameraImagePreview.prepare(file, CameraPreviewSettings(autoStretch = false, monochrome = true))
            val crop = CameraImagePreview.renderRegion(mono, MediaImageRegion(13, 17, 24, 16))
            try {
                assertTrue(pixels(colour.bitmap).any { Color.green(it) > Color.red(it) })
                for (pixel in pixels(mono.bitmap) + pixels(crop)) {
                    assertEquals(Color.red(pixel), Color.green(pixel)); assertEquals(Color.green(pixel), Color.blue(pixel))
                }
                assertArrayEquals(original, file.readBytes())
                assertEquals("RGGB", com.indigo.mobileobservatory.util.FitsHeaderParser.read(file)["BAYERPAT"])
            } finally { colour.bitmap.recycle(); mono.bitmap.recycle(); crop.recycle() }
        } finally { file.delete() }
    }

    @Test fun importedPngKeepsTheCameraRgbDisplayAndNativeCrop() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "camera-${UUID.randomUUID()}.png")
        val original = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 96) original.setPixel(x, y, Color.rgb(x * 2, y * 3, x + y))
        try {
            file.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val prepared = CameraImagePreview.prepare(file, CameraPreviewSettings(lp700 = true, redGain = 2f))
            val crop = CameraImagePreview.renderRegion(prepared, MediaImageRegion(13, 17, 24, 16))
            try {
                assertArrayEquals(pixels(original), pixels(prepared.bitmap))
                for (y in 0 until 16) for (x in 0 until 24) assertEquals(original.getPixel(x + 13, y + 17), crop.getPixel(x, y))
            } finally { prepared.bitmap.recycle(); crop.recycle() }
        } finally { original.recycle(); file.delete() }
    }

    @Test fun polarDisplayFollowsTheCameraRecipeWithoutAnotherAdjustmentPanel() {
        val file = sampleFile()
        val data = ByteBuffer.allocate(192 * 128 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(192 * 128) { data.putShort(8192.toShort()) }
        try {
            FITSWriter().write(file, FrameData(data.array(), 192, 128, PixelFormat.MONO16, 1, 0), 1f, 0f)
            val settings = mutableStateOf(CameraPreviewSettings(autoStretch = false))
            compose.setContent { MobileObservatoryTheme {
                PolarWorkflowPreview(file, "Camera recipe", null, null, "", null, false, {}, null, {}, previewSettings = settings.value)
            } }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() }
            fun center() = compose.onNodeWithTag("polar-main-preview").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2].red }
            assertTrue(center() > .08f)
            compose.runOnIdle { settings.value = settings.value.copy(autoStretch = true) }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() && center() < .03f
            }
        } finally { file.delete() }
    }

    @Test fun savedCameraFramesUseTheLiveWhiteBalanceStretchAndLp700Recipe() {
        val settings = CameraPreviewSettings(autoStretch = false, blackPoint = 7, whitePoint = 215,
            redGain = 1.3f, greenGain = .85f, blueGain = 1.6f, lp700 = true)
        for (format in listOf(PixelFormat.BAYER_RG8, PixelFormat.BAYER_GR12, PixelFormat.BAYER_GB16)) {
            val frame = scene(96, 64, format)
            val live = FrameProcessor().apply { applyPreviewSettings(settings) }.frameToBitmap(frame,
                PixelTraits(validBits = format.nativeBits, alignment = SampleAlignment.RIGHT))
            val file = sampleFile()
            try {
                FITSWriter().write(file, frame, 1f, 0f)
                val original = file.readBytes()
                val saved = CameraImagePreview.prepare(file, settings)
                try { assertArrayEquals(pixels(live), pixels(saved.bitmap)); assertArrayEquals(original, file.readBytes()) }
                finally { saved.bitmap.recycle() }
            } finally { file.delete(); live.recycle() }
        }
    }

    @Test fun nativeBayerRegionsKeepTheOverviewStretchAndNeighbouringCfaSamples() {
        val frame = scene(128, 96, PixelFormat.BAYER_RG16)
        val file = sampleFile()
        try {
            FITSWriter().write(file, frame, 1f, 0f)
            val prepared = CameraImagePreview.prepare(file, CameraPreviewSettings(redGain = 1.2f, blueGain = .8f, lp700 = true))
            val region = MediaImageRegion(31, 23, 40, 30)
            val native = CameraImagePreview.renderRegion(prepared, region)
            try {
                for (y in 0 until region.height) for (x in 0 until region.width) {
                    assertEquals("x=$x y=$y", prepared.bitmap.getPixel(x + region.left, y + region.top), native.getPixel(x, y))
                }
            } finally { native.recycle(); prepared.bitmap.recycle() }
        } finally { file.delete() }
    }

    @Test fun thumbnailsScaleTheSameProcessedOverview() {
        val file = sampleFile()
        try {
            FITSWriter().write(file, scene(1024, 768, PixelFormat.BAYER_RG12), 1f, 0f)
            val settings = CameraPreviewSettings(redGain = 1.2f, blueGain = 1.4f)
            val overview = CameraImagePreview.prepare(file, settings)
            val thumbnail = CameraImagePreview.prepare(file, settings, 480)
            val expected = Bitmap.createScaledBitmap(overview.bitmap, 480, 360, true)
            try { assertArrayEquals(pixels(expected), pixels(thumbnail.bitmap)) }
            finally { expected.recycle(); thumbnail.bitmap.recycle(); overview.bitmap.recycle() }
        } finally { file.delete() }
    }

    private fun scene(w: Int, h: Int, format: PixelFormat): FrameData {
        val bits = format.nativeBits
        val data = ByteBuffer.allocate(w * h * format.bytesPerPixel).order(ByteOrder.LITTLE_ENDIAN)
        val max = (1 shl bits) - 1
        for (y in 0 until h) for (x in 0 until w) {
            val code = ((.08 + .5 * x / w + .15 * y / h) * max).toInt()
            if (format.is8bit) data.put(code.toByte()) else data.putShort(code.toShort())
        }
        return FrameData(data.array(), w, h, format, 1, 0)
    }
    private fun sampleFile() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "camera-recipe-${UUID.randomUUID()}.fits")
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    @Test fun sparseStarsKeepTheContrastOfTheLiveCameraPreview() {
        val width = 384; val height = 256
        val bytes = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) {
            val star = kotlin.math.abs(x - width / 2) <= 1 && kotlin.math.abs(y - height / 2) <= 1
            bytes.putShort((if (star) 3000 else 1000).toShort())
        }
        val frame = FrameData(bytes.array(), width, height, PixelFormat.MONO16, 1, 0)
        val live = FrameProcessor().frameToBitmap(frame)
        assertTrue(Color.red(live.getPixel(width / 2, height / 2)) > 240)
        assertTrue(Color.red(live.getPixel(width / 2 - 20, height / 2)) < 20)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "polar-contrast-${UUID.randomUUID()}.fits")
        try {
            FITSWriter().write(file, frame, 1f, 0f)
            compose.setContent { MobileObservatoryTheme {
                PolarWorkflowPreview(file, "Test image", null, null, "", null, false, {}, null, {})
            } }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() }
            val image = compose.onNodeWithTag("polar-main-preview").captureToImage().toPixelMap()
            val scale = min(image.width.toFloat() / width, image.height.toFloat() / height)
            val offset = Offset((image.width - width * scale) / 2, (image.height - height * scale) / 2)
            fun level(x: Int, y: Int): Float = image[(offset.x + (x + .5f) * scale).toInt(), (offset.y + (y + .5f) * scale).toInt()].red
            assertTrue("Sparse-star background must remain dark, as it does in the live preview", level(width / 2 - 20, height / 2) < .2f)
            assertTrue("The captured star must remain visible", level(width / 2, height / 2) > .8f)
        } finally { file.delete(); live.recycle() }
    }
}
