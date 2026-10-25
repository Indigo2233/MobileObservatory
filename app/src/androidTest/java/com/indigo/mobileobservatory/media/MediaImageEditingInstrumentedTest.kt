package com.indigo.mobileobservatory.media

import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.screens.PlayerScreen
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class MediaImageEditingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    @Before fun prepareDirectory() { root = File(context.cacheDir, "album-edit-${UUID.randomUUID()}").apply { check(mkdirs()) } }
    @After fun cleanDirectory() { root.deleteRecursively() }

    @Test fun allFormatsRetainOriginalDimensionsAndFitsPrecisionWithMatchingPreview() {
        val source = File(root, "source.fit")
        val original = MediaImagePixels(1801, 3, 3, FloatArray(1801 * 3 * 3) { index ->
            .10001f + (index % 179) * .003317f
        })
        MediaImageFitsCodec.write(source, original, mapOf("schema" to "indigo-image-edit-1"))
        val hash = digest(source)
        val prepared = MediaImageEditing.prepare(source)
        assertTrue(prepared.preview.width < original.width)
        val settings = MediaImageEditSettings(redGain = 1.1f, blueGain = .9f, gamma = 1.3f, exposureStops = .2f)
        val expected = original.deepCopy()
        MediaImageProcessing.apply(expected, settings, prepared.lpGains)
        val fit = MediaImageEditing.export(prepared, root, MediaImageExportFormat.FIT, settings)
        val actual = MediaImageFitsCodec.read(fit).image
        assertEquals(1801, actual.width); assertEquals(3, actual.height); assertEquals(3, actual.channels)
        assertArrayEquals(expected.pixels, actual.pixels, 0f)
        for (format in listOf(MediaImageExportFormat.JPG, MediaImageExportFormat.PNG)) {
            val output = MediaImageEditing.export(prepared, root, format, settings)
            val bitmap = checkNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                assertEquals(original.width, bitmap.width); assertEquals(original.height, bitmap.height)
                if (format == MediaImageExportFormat.PNG) for (x in listOf(0, 15, 1000, 1800)) {
                    val pixel = bitmap.getPixel(x, 1)
                    for (c in 0..2) assertEquals((expected.pixels[(1801 + x) * 3 + c] * 255).roundToInt(),
                        (pixel ushr (16 - c * 8)) and 255)
                }
            } finally { bitmap.recycle() }
        }
        val preview = MediaImageEditing.renderPreview(prepared, settings)
        try {
            val x = 7
            val pixel = preview.getPixel(x, 0)
            for (c in 0..2) assertEquals((expected.pixels[x * 2 * 3 + c] * 255).roundToInt(),
                (pixel ushr (16 - c * 8)) and 255)
        } finally { preview.recycle() }
        assertArrayEquals(hash, digest(source))
    }

    @Test fun cancellationCleansTemporaryOutputsAndChangedSourcesCannotBeSaved() {
        val source = File(root, "source.fit")
        MediaImageFitsCodec.write(source, MediaImagePixels(8, 8, 1, FloatArray(64) { .250001f }), emptyMap())
        val prepared = MediaImageEditing.prepare(source)
        val outputDirectory = File(root, "FITS")
        assertThrows(CancellationException::class.java) {
            MediaImageEditing.export(prepared, root, MediaImageExportFormat.FIT, MediaImageEditSettings()) {
                outputDirectory.listFiles().orEmpty().any { it.name.endsWith(".partial") }
            }
        }
        assertTrue(outputDirectory.listFiles().orEmpty().isEmpty())
        source.appendBytes(byteArrayOf(0))
        assertThrows(IllegalArgumentException::class.java) {
            MediaImageEditing.export(prepared, root, MediaImageExportFormat.PNG, MediaImageEditSettings())
        }
    }

    @Test fun imageViewerExposesEditingAndOpensSavedRgbFits() {
        val captures = File(root, "captures")
        val source = File(captures, "FITS/fixture.fit").apply { parentFile!!.mkdirs() }
        MediaImageFitsCodec.write(source, MediaImagePixels(16, 12, 3, FloatArray(16 * 12 * 3) {
            when (it % 3) { 0 -> .3f; 1 -> .4f; else -> .6f }
        }), mapOf("schema" to "indigo-image-edit-1"))
        compose.setContent { MaterialTheme {
            PlayerScreen(File(root, "recordings"), captures, initialFile = source, onBack = {})
        } }
        val edit = context.getString(R.string.media_edit_action)
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(edit).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(edit).performClick()
        val save = context.getString(R.string.media_edit_export_fit)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(save).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(save).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { source.parentFile!!.listFiles().orEmpty().any { it.extension == "fit" && it != source } }
        val result = source.parentFile!!.listFiles()!!.first { it.extension == "fit" && it != source }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.media_edit_view_saved)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.media_edit_view_saved)).performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(result.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.image_desc)).assertIsDisplayed()
        assertEquals(3, MediaImageFitsCodec.read(result).image.channels)
        assertTrue(source.isFile)
    }

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
