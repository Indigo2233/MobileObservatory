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

@RunWith(AndroidJUnit4::class)
class MediaImageEditingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    @Before fun prepareDirectory() { root = File(context.cacheDir, "album-edit-${UUID.randomUUID()}").apply { check(mkdirs()) } }
    @After fun cleanDirectory() { root.deleteRecursively() }

    @Test fun allFormatsRetainOriginalDimensionsAndFitsPrecisionWithMatchingPreview() {
        val source = File(root, "source.fit")
        val original = MediaImagePixels(1801, 3, 3, ShortArray(1801 * 3 * 3) { index -> (6554 + (index % 179) * 217).toShort() })
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
        assertArrayEquals(expected.pixels, actual.pixels)
        for (format in listOf(MediaImageExportFormat.JPG, MediaImageExportFormat.PNG)) {
            val output = MediaImageEditing.export(prepared, root, format, settings)
            val bitmap = checkNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                assertEquals(original.width, bitmap.width); assertEquals(original.height, bitmap.height)
                if (format == MediaImageExportFormat.PNG) for (x in listOf(0, 15, 1000, 1800)) {
                    MediaPng16Codec.open(output).use { source16 ->
                        val sample = source16.read(MediaImageRegion(x, 1, 1, 1))
                        for (c in 0..2) assertEquals(expected.unsigned((1801 + x) * 3 + c), sample.unsigned(c))
                    }
                }
            } finally { bitmap.recycle() }
        }
        val preview = MediaImageEditing.renderPreview(prepared, settings)
        try {
            val x = 7
            val pixel = preview.getPixel(x, 0)
            for (c in 0..2) assertEquals((expected.unsigned(x * 2 * 3 + c) + 128) / 257,
                (pixel ushr (16 - c * 8)) and 255)
        } finally { preview.recycle() }
        assertArrayEquals(hash, digest(source))
    }

    @Test fun cancellationCleansTemporaryOutputsAndChangedSourcesCannotBeSaved() {
        val source = File(root, "source.fit")
        MediaImageFitsCodec.write(source, MediaImagePixels(8, 8, 1, ShortArray(64) { 16384 }), emptyMap())
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

    @Test fun nativeViewingAndSavedRecipesUseTheOriginalPixels() {
        val source = File(root, "native-source.fit")
        val pixels = MediaImagePixels(1801, 4, 3, ShortArray(1801 * 4 * 3) { (6554 + (it % 211) * 197).toShort() })
        MediaImageFitsCodec.write(source, pixels, emptyMap())
        val prepared = MediaImageEditing.prepare(source)
        val settings = MediaImageEditSettings(gamma = 1.6f, redGain = 1.2f)
        val store = MediaEditRecipeStore(File(root, "recipes"))
        store.save(prepared, settings)
        val recipe = checkNotNull(store.load(source))
        val reopened = MediaImageEditing.prepare(source, recipe)
        val native = MediaImageEditing.renderFullResolution(reopened, recipe.settings)
        val exported = MediaImageEditing.export(prepared, root, MediaImageExportFormat.PNG, settings)
        val saved = checkNotNull(BitmapFactory.decodeFile(exported.absolutePath))
        try {
            assertEquals(1801, native.width); assertEquals(4, native.height)
            assertTrue(native.width > prepared.preview.width)
            MediaPng16Codec.open(exported).use { source16 ->
                val samples = source16.read(MediaImageRegion(0, 0, 1801, 4))
                for (x in listOf(0, 1000, 1800)) for (c in 0..2) assertEquals((samples.unsigned(x * 3 + c) + 128) / 257,
                    native.getPixel(x, 0) ushr (16 - c * 8) and 255)
            }
            val region = MediaImageEditing.renderRegion(reopened, recipe.settings, MediaImageRegion(100, 1, 512, 2))
            try { assertEquals(512, region.width); assertEquals(native.getPixel(211, 2), region.getPixel(111, 1)) } finally { region.recycle() }
            assertEquals(source, MediaLineage.recipeSource(recipe, listOf(checkNotNull(com.indigo.mobileobservatory.recording.MediaLibrary.describe(source)))))
        } finally { native.recycle(); saved.recycle() }
    }


    @Test fun imageViewerExposesEditingAndOpensSavedRgbFits() {
        val captures = File(root, "captures")
        val source = File(captures, "FITS/fixture.fit").apply { parentFile!!.mkdirs() }
        MediaImageFitsCodec.write(source, MediaImagePixels(16, 12, 3, ShortArray(16 * 12 * 3) { when (it % 3) { 0 -> 19661; 1 -> 26214; else -> 39321.toShort() } }), mapOf("schema" to "indigo-image-edit-1"))
        compose.setContent { MaterialTheme {
            PlayerScreen(File(root, "recordings"), captures, initialFile = source, onBack = {})
        } }
        val edit = context.getString(R.string.media_edit_action)
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription(edit) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
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
