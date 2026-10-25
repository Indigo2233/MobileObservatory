package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.recording.MediaLibrary
import com.indigo.mobileobservatory.recording.VideoFileInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaAlbumWorkflowTest {
    @Test fun sliderGestureIsOneUndoStepAndBranchingClearsRedo() {
        val first = MediaImageEditSettings()
        val changed = first.copy(gamma = 1.7f)
        var history = MediaEditHistory(first)
        repeat(40) { history = history.update(first.copy(gamma = 1f + it / 100f)) }
        history = history.update(changed).commit()
        assertEquals(listOf(first), history.past)
        history = history.undo()
        assertEquals(first, history.current)
        assertEquals(changed, history.redo().current)
        history = history.change(first.copy(lp700 = true))
        assertTrue(history.future.isEmpty())
        assertEquals(history, history.redo())
        repeat(100) { history = history.change(first.copy(gamma = 1f + it / 100f)) }
        assertEquals(80, history.past.size)
    }

    @Test fun recipesPreserveNumericalDomainAndRejectUnsafeParameters() = directory { root ->
        val source = File(root, "source.fit").apply { writeText("source") }
        val prepared = prepared(source)
        val settings = MediaImageEditSettings(lp700 = true, gamma = 1.6f, exposureStops = -.7f)
        val recipe = MediaEditRecipe.from(prepared, settings)
        assertEquals(recipe, MediaEditRecipe.parse(recipe.metadata()))
        assertNull(MediaEditRecipe.parse(recipe.metadata() + ("redGain" to "NaN")))
        assertNull(MediaEditRecipe.parse(recipe.metadata() + ("gamma" to "200")))
        assertNull(MediaEditRecipe.parse(recipe.metadata() + ("lpGains" to "1,Infinity,1")))
        assertNull(MediaEditRecipe.parse(recipe.metadata() + ("normalizationWhite" to "-100")))
        val store = MediaEditRecipeStore(File(root, "recipes"))
        store.save(prepared, settings)
        assertEquals(recipe, store.load(source))
        store.save(prepared, settings.copy(lp700 = false))
        assertFalse(store.load(source)!!.settings.lp700)
        source.appendText("changed")
        assertNull(store.load(source))
        assertFalse(File(root, "recipes").listFiles()!!.any { it.name.endsWith("partial") })
    }

    @Test fun returningToAnImageKeepsHistoryAndItsSavedCheckpoint() {
        val editor = MediaImageEditViewModel()
        val saved = MediaImageEditSettings(gamma = 1.3f)
        editor.bind("source-a", saved)
        val adjustment = saved.copy(exposureStops = 1f)
        editor.change(adjustment)
        editor.bind("source-a", MediaImageEditSettings())
        assertEquals(adjustment, editor.history.value.current)
        assertEquals(saved, editor.checkpoint.value)
        editor.markSaved(adjustment)
        assertEquals(adjustment, editor.checkpoint.value)
        val newerRecipe = adjustment.copy(gamma = 1.8f)
        editor.bind("source-a", newerRecipe)
        assertEquals(newerRecipe, editor.history.value.current)
        assertEquals(newerRecipe, editor.checkpoint.value)
        assertTrue(editor.history.value.past.isEmpty())
        editor.bind("source-b", MediaImageEditSettings())
        assertTrue(editor.history.value.past.isEmpty())
        assertEquals(MediaImageEditSettings(), editor.checkpoint.value)
    }

    @Test fun originalStackAndEditsAreGroupedWithSafeSourceResolution() = directory { root ->
        val original = File(root, "capture.ser").apply { writeText("capture") }
        val linear = File(root, "stack-linear.fits")
        val png = File(root, "stack.png").apply { writeText("processed") }
        val edited = File(root, "edited.png").apply { writeText("edited") }
        val metadata = mapOf("schema" to "indigo-stack-1", "source" to original.name,
            "sourceIdentity" to "${original.canonicalPath}|${original.length()}|${original.lastModified()}|prefix-sha256:123",
            "linearFits" to linear.name, "processedPng" to png.name)
        MediaImageFitsCodec.write(linear, MediaImagePixels(1, 1, 1, shortArrayOf(13107)), metadata)
        MediaLineage.sidecar(png).writeText(JSONObject(metadata).toString())
        val recipe = MediaEditRecipe.from(prepared(linear), MediaImageEditSettings(gamma = 1.4f))
        MediaLineage.sidecar(edited).writeText(JSONObject(recipe.metadata()).toString())
        val all = listOf(original, linear, png, edited).map { MediaLibrary.describe(it)!! }
        val groups = MediaLineage.groups(all)
        assertEquals(1, groups.size)
        assertEquals(4, groups.single().members.size)
        assertEquals(linear, MediaLineage.recipeSource(recipe, all))
        // Metadata alone cannot turn an arbitrary file outside the indexed library into a source.
        assertNull(MediaLineage.recipeSource(recipe, listOf(MediaLibrary.describe(edited)!!)))
        original.appendText("changed")
        assertEquals(2, MediaLineage.groups(all).size)
    }

    @Test fun ambiguousOrMissingStackSiblingsRemainSeparate() = directory { root ->
        val first = File(root, "a/linear.fits").apply { parentFile!!.mkdirs(); writeText("first") }
        val second = File(root, "b/linear.fits").apply { parentFile!!.mkdirs(); writeText("second") }
        val png = File(root, "render.png").apply { writeText("image") }
        MediaLineage.sidecar(png).writeText(JSONObject(mapOf("schema" to "indigo-stack-1",
            "linearFits" to "linear.fits", "processedPng" to "render.png")).toString())
        assertEquals(3, MediaLineage.groups(listOf(first, second, png).map { MediaLibrary.describe(it)!! }).size)
    }

    @Test fun batchArchiveKeepsDuplicateNamesSidecarsAndPortableGroups() = directory { root ->
        val first = File(root, "a/photo.png").apply { parentFile!!.mkdirs(); writeText("one") }
        val second = File(root, "b/photo.png").apply { parentFile!!.mkdirs(); writeText("two") }
        MediaLineage.sidecar(second).writeText(JSONObject(MediaEditRecipe.from(prepared(first), MediaImageEditSettings()).metadata()).toString())
        val bytes = ByteArrayOutputStream()
        MediaBatchArchive.write(bytes, listOf(first, second).map { MediaLibrary.describe(it)!! })
        val contents = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; contents[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals("one", contents["1/photo.png"])
        assertEquals("two", contents["2/photo.png"])
        assertTrue(contents.containsKey("2/photo.json"))
        val manifest = JSONObject(contents.getValue("manifest.json")).getJSONArray("files")
        assertEquals(manifest.getJSONObject(0).getInt("group"), manifest.getJSONObject(1).getInt("group"))
        assertTrue(contents.keys.none { it.contains("..") || it.startsWith('/') })
    }

    @Test fun batchCancellationAndChangedSourcesAreDetected() = directory { root ->
        val source = File(root, "capture.ser").apply { writeBytes(ByteArray(200_000)) }
        val info = MediaLibrary.describe(source)!!
        var checks = 0
        try {
            MediaBatchArchive.write(ByteArrayOutputStream(), listOf(info), { if (++checks == 4) throw CancellationException() })
            fail("Cancellation was ignored")
        } catch (_: CancellationException) { }
        source.appendText("changed")
        try {
            MediaBatchArchive.write(ByteArrayOutputStream(), listOf(info)); fail("Changed source accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun histogramMeasuresRgbClippingAtDisplayWhite() {
        val result = MediaDisplayHistogram.fromArgb(intArrayOf(0xff000000.toInt(), 0xffff4000.toInt(), 0xff10ff10.toInt(), 0xff808080.toInt()))
        assertEquals(4, result.samples); assertEquals(2, result.clipped)
        assertEquals(1, result.bins[0][255]); assertEquals(1, result.bins[1][255])
        assertTrue(result.bins.all { it.sum() == 4 })
    }

    @Test fun trashRestoresEditedImageAndRecipeToTheirExactOriginalLocation() = directory { root ->
        val captures = File(root, "captures")
        val image = File(captures, "FITS/edited.fit").apply { parentFile!!.mkdirs(); writeText("pixels") }
        val sidecar = MediaLineage.sidecar(image).apply { writeText("recipe") }
        val trash = File(root, "recordings/.trash")
        assertTrue(MediaTrash.move(image, trash))
        assertFalse(image.exists()); assertFalse(sidecar.exists())
        val deleted = File(trash, image.name)
        assertTrue(MediaLineage.sidecar(deleted).exists())
        assertTrue(MediaTrash.restore(deleted, File(root, "wrong-folder"), listOf(captures)))
        assertEquals("pixels", image.readText()); assertEquals("recipe", sidecar.readText())
        assertTrue(trash.listFiles()!!.isEmpty())
        assertTrue(MediaTrash.move(image, trash))
        image.writeText("new-image")
        assertFalse(MediaTrash.restore(deleted, captures, listOf(captures)))
        assertEquals("new-image", image.readText())
        MediaTrash.delete(deleted)
        assertTrue(trash.listFiles()!!.isEmpty())
    }

    private fun prepared(source: File) = PreparedMediaImage(source, MediaImageEditing.fingerprint(source), 1, 1,
        MediaImagePixels(1, 1, 3, shortArrayOf(6554, 13107, 19661)), MediaImageWindow(-100f, 64000f), floatArrayOf(.8f, 1.1f, 1.5f), emptyMap())

    private fun directory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("album-workflow").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
