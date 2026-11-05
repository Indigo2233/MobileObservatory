package com.indigo.mobileobservatory.media

import com.indigo.mobileobservatory.stacking.LinearImage
import com.indigo.mobileobservatory.stacking.StackFitsReader
import com.indigo.mobileobservatory.stacking.StackFitsWriter
import com.indigo.mobileobservatory.recording.MediaLibrary
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaRenameTest {
    private fun directory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("media-rename").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun renameMovesCaptureAndEditSidecarsAndUpdatesMetadataReferences() = directory { root ->
        val source = File(root, "capture.ser").apply { writeText("frames") }
        File(root, "capture.ser.capture.json").writeText(JSONObject()
            .put("valid", true).put("headerIdentity", "hash").toString())
        val solveLog = com.indigo.mobileobservatory.astrometry.SolveFailureArchive.logFor(source).apply { writeText("log") }
        val sibling = File(root, "stack-linear.fits").apply { writeText("pixels") }
        MediaLineage.sidecar(sibling).writeText(JSONObject()
            .put("sourceFingerprint", "${source.absolutePath}:${source.length()}:${source.lastModified()}")
            .put("source", source.name).toString())
        val indexed = listOf(MediaLibrary.describe(source)!!, MediaLibrary.describe(sibling)!!)

        val renamed = MediaRename.rename(source, " M42 H-alpha ", indexed)

        assertEquals("M42 H-alpha.ser", renamed.name)
        assertFalse(source.exists())
        assertTrue(File(root, "M42 H-alpha.ser.capture.json").isFile)
        assertTrue(File(root, "M42 H-alpha.ser.solve.txt").isFile)
        assertFalse(solveLog.exists())
        val metadata = JSONObject(MediaLineage.sidecar(sibling).readText())
        assertEquals("M42 H-alpha.ser", metadata.getString("source"))
        assertEquals("${renamed.absolutePath}:${renamed.length()}:${renamed.lastModified()}",
            metadata.getString("sourceFingerprint"))
    }

    @Test fun renameRejectsInvalidOrExistingNamesWithoutChangingFiles() = directory { root ->
        val source = File(root, "capture.png").apply { writeText("pixels") }
        File(root, "taken.png").writeText("other")
        for (candidate in listOf("", "   ", "bad/name", "bad?name", ".".repeat(2), ".hidden", ".M42", "taken")) {
            try {
                MediaRename.rename(source, candidate)
                fail("Expected rejection for '$candidate'")
            } catch (_: IllegalArgumentException) {
                assertTrue(source.isFile)
            }
        }
        assertFalse(File(root, "taken.png").readText() == "pixels")
    }

    @Test fun renameKeepsPipeSeparatedSourceIdentityAndAlbumGrouping() = directory { root ->
        val recording = File(root, "capture.ser").apply { writeText("frames") }
        val identity = "${recording.canonicalPath}|${recording.length()}|${recording.lastModified()}|prefix-sha256:abc"
        val linear = File(root, "stack-linear.fits")
        StackFitsWriter.write(linear, LinearImage(2, 2, 1, floatArrayOf(.1f, .2f, .3f, .4f)), mapOf(
            "schema" to "indigo-stack-1", "source" to recording.name, "sourceIdentity" to identity,
            "linearFits" to linear.name, "processedPng" to "stack.png"))
        val png = File(root, "stack.png").apply { writeText("pixels") }
        val displayMetadata = JSONObject()
            .put("schema", "indigo-stack-1").put("source", recording.name).put("sourceIdentity", identity)
            .put("linearFits", linear.name).put("processedPng", png.name)
        MediaLineage.sidecar(png).writeText(displayMetadata.toString())
        val indexed = listOf(recording, linear, png).map { MediaLibrary.describe(it)!! }
        assertEquals(1, MediaLineage.groups(indexed).size)

        val renamed = MediaRename.rename(recording, "M42 Flat", indexed)

        val expectedPrefix = "${renamed.canonicalPath}|"
        assertTrue(StackFitsReader.readMetadata(linear)["sourceIdentity"]!!.startsWith(expectedPrefix))
        assertTrue(JSONObject(MediaLineage.sidecar(png).readText()).getString("sourceIdentity").startsWith(expectedPrefix))
        val regrouped = listOf(renamed, linear, png).map { MediaLibrary.describe(it)!! }
        assertEquals(1, MediaLineage.groups(regrouped).size)
    }

    @Test fun renameUpdatesFitsMetadataAndPreservesImageBytes() = directory { root ->
        val recording = File(root, "capture.ser").apply { writeText("frames") }
        val identity = "${recording.canonicalPath}|${recording.length()}|${recording.lastModified()}|prefix-sha256:abc"
        val linear = File(root, "stack-linear.fits")
        val pixels = FloatArray(64 * 64) { it / 4096f }
        StackFitsWriter.write(linear, LinearImage(64, 64, 1, pixels), mapOf(
            "schema" to "indigo-stack-1", "source" to recording.name, "sourceIdentity" to identity,
            "padding" to "x".repeat(2500)))
        val before = StackFitsReader.read(linear).pixels

        val renamed = MediaRename.rename(recording, "A".repeat(96), listOf(MediaLibrary.describe(linear)!!))

        assertArrayEquals(before, StackFitsReader.read(linear).pixels, 0f)
        assertTrue(StackFitsReader.readMetadata(linear)["sourceIdentity"]!!.startsWith("${renamed.canonicalPath}|"))
    }

    @Test fun renameKeepsSavedEditRecipeLoadableForRewrittenFits() = directory { root ->
        val linear = File(root, "stack-linear.fits")
        val identity = "${linear.canonicalPath}|1|1|prefix-sha256:abc"
        StackFitsWriter.write(linear, LinearImage(4, 4, 1, FloatArray(16) { it / 16f }), mapOf(
            "schema" to "indigo-stack-1", "source" to "capture.ser", "sourceIdentity" to identity))
        val store = MediaEditRecipeStore(File(root, "recipes"))
        val settings = MediaImageEditSettings(gamma = 1.4f)
        store.save(com.indigo.mobileobservatory.media.MediaImageEditing.prepare(linear), settings)

        val renamed = MediaRename.rename(linear, "stack-renamed", listOf(MediaLibrary.describe(linear)!!))
        store.rename(linear, renamed)

        // FITS metadata rewriting changes length/mtime, so the recipe must be re-fingerprinted
        // from the renamed file instead of reusing the stale tail of the old fingerprint.
        assertEquals(settings, store.load(renamed)!!.settings)
    }

    @Test fun renamingSiblingRefreshesRecipeOfRewrittenFits() = directory { root ->
        val recording = File(root, "capture.ser").apply { writeText("frames") }
        val linear = File(root, "stack-linear.fits")
        val identity = "${recording.canonicalPath}|${recording.length()}|${recording.lastModified()}|prefix-sha256:abc"
        StackFitsWriter.write(linear, LinearImage(4, 4, 1, FloatArray(16) { it / 16f }), mapOf(
            "schema" to "indigo-stack-1", "source" to recording.name, "sourceIdentity" to identity))
        val store = MediaEditRecipeStore(File(root, "recipes"))
        val settings = MediaImageEditSettings(gamma = 1.25f)
        store.save(com.indigo.mobileobservatory.media.MediaImageEditing.prepare(linear), settings)
        val indexed = listOf(MediaLibrary.describe(linear)!!)

        // Renaming the recording rewrites the sibling FITS metadata, which changes its
        // length/mtime; the recipe fingerprint must follow the rewritten content.
        MediaRename.rename(recording, "M42 Light", indexed, onRewritten = { store.refresh(it) })

        assertEquals(settings, store.load(linear)!!.settings)
    }
}
