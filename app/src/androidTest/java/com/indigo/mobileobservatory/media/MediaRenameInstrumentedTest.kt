package com.indigo.mobileobservatory.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.indigo.mobileobservatory.astrometry.SolveFailureArchive
import com.indigo.mobileobservatory.recording.MediaLibrary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaRenameInstrumentedTest {
    @Test fun renameKeepsCaptureContextAndLineageOnAndroidStorage() {
        val root = File.createTempFile("media-rename", "").let { temporary ->
            temporary.delete()
            temporary.mkdirs()
            temporary
        }
        try {
            val source = File(root, "capture.ser").apply { writeText("frames") }
            File(root, "capture.ser.capture.json").writeText("""{"valid":true}""")
            SolveFailureArchive.logFor(source).writeText("diagnostics")
            val sibling = File(root, "stack-linear.fits").apply { writeText("pixels") }
            MediaLineage.sidecar(sibling).writeText(
                """{"source":"${source.name}","sourceFingerprint":"${source.absolutePath}:${source.length()}:${source.lastModified()}"}"""
            )
            val indexed = listOf(MediaLibrary.describe(source)!!, MediaLibrary.describe(sibling)!!)

            val renamed = MediaRename.rename(source, "M42 Flat", indexed)

            assertEquals("M42 Flat.ser", renamed.name)
            assertFalse(source.exists())
            assertTrue(File(root, "M42 Flat.ser.capture.json").isFile)
            assertTrue(File(root, "M42 Flat.ser.solve.txt").isFile)
            assertTrue(MediaLineage.sidecar(sibling).readText().contains("M42 Flat.ser"))
        } finally {
            root.deleteRecursively()
        }
    }
}
