package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.FITSWriter
import com.indigo.mobileobservatory.recording.MediaLibrary
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SolveFailureArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedImageAndFullLogAreDiscoverableWithoutChangingTheOriginal() {
        val source = temporary.newFile("source.fits")
        val bytes = ByteArray(130_000) { (it % 251).toByte() }
        source.writeBytes(bytes)
        val modified = source.lastModified()
        val log = "command=astap -fov 1.5\n" + "Found 80 stars; no match\n".repeat(1000)
        val captures = temporary.newFolder("captures")
        val saved = requireNotNull(SolveFailureArchive.save(captures, source, log))
        assertArrayEquals(bytes, saved.image.readBytes())
        assertArrayEquals(bytes, source.readBytes())
        assertEquals(modified, source.lastModified())
        assertEquals(log, saved.log.readText())
        assertEquals(listOf(saved.image), MediaLibrary.listImages(null, captures).map { it.file })
        assertFalse(captures.walkTopDown().any { it.name.endsWith(".partial") })
    }

    @Test fun countRetentionSpansFitsAndJpegAndOnlyDeletesArchivePairs() {
        val captures = temporary.newFolder("captures")
        val source = temporary.newFile("source.fits").apply { writeText("pixels") }
        val first = requireNotNull(SolveFailureArchive.save(captures, source, "first", maxEntries = 2))
        assertTrue(first.image.setLastModified(1_000))
        val jpeg = temporary.newFile("source.jpg").apply { writeText("jpeg") }
        val second = requireNotNull(SolveFailureArchive.save(captures, jpeg, "second", maxEntries = 2))
        assertTrue(second.image.setLastModified(2_000))
        val regular = File(first.image.parentFile, "user-image.fits").apply { writeText("original") }
        val unrelated = File(first.image.parentFile, "solve-failed-user.fits").apply { writeText("original") }
        val third = requireNotNull(SolveFailureArchive.save(captures, source, "third", maxEntries = 2))
        assertFalse(first.image.exists()); assertFalse(first.log.exists())
        assertTrue(second.image.exists()); assertTrue(second.log.exists())
        assertTrue(third.image.exists()); assertTrue(third.log.exists())
        assertEquals("original", regular.readText()); assertEquals("original", unrelated.readText())
    }

    @Test fun byteLimitIncludesBothTheImageAndLog() {
        val captures = temporary.newFolder("captures")
        val source = temporary.newFile("source.fits").apply { writeBytes(ByteArray(60)) }
        val first = requireNotNull(SolveFailureArchive.save(captures, source, "log".repeat(10), maxBytes = 150))
        val second = requireNotNull(SolveFailureArchive.save(captures, source, "log".repeat(10), maxBytes = 150))
        assertFalse(first.image.exists()); assertFalse(first.log.exists())
        assertTrue(second.image.exists()); assertTrue(second.log.exists())
        assertTrue(captures.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 150)
        try {
            SolveFailureArchive.save(captures, source, "x".repeat(100), maxBytes = 150)
            fail("Oversized evidence accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(second.image.exists()); assertTrue(second.log.exists())
    }

    @Test fun cancellationDuringCopyLeavesNoPartialFilesAndPreservesTheSource() {
        val captures = temporary.newFolder("captures")
        val source = temporary.newFile("source.fits").apply { writeBytes(ByteArray(150_000) { 7 }) }
        var checks = 0
        try {
            SolveFailureArchive.save(captures, source, "diagnostics", checkActive = {
                if (++checks == 3) throw CancellationException("Stopped")
            })
            fail("Cancellation ignored")
        } catch (_: CancellationException) { }
        assertEquals(150_000L, source.length())
        assertFalse(captures.walkTopDown().any { it.isFile })
    }

    @Test fun simulatedFailureDoesNotEnterTheAlbumOrEvictRealEvidence() {
        val captures = temporary.newFolder("captures")
        val source = writeFits("sky.fits", "LIGHT")
        val saved = requireNotNull(SolveFailureArchive.save(captures, source, "real failure", maxEntries = 1))
        // A renamed simulation must still be excluded, even when the failure log has no marker.
        val simulated = writeFits("renamed.fits", "SIMULATION")
        val filesBefore = captures.walkTopDown().filter { it.isFile }.toSet()
        assertNull(SolveFailureArchive.save(captures, simulated, "", maxEntries = 1))
        assertEquals(filesBefore, captures.walkTopDown().filter { it.isFile }.toSet())
        assertEquals(listOf(saved.image), MediaLibrary.listImages(null, captures).map { it.file })
        assertTrue(simulated.isFile)
    }

    @Test fun realImageWithSimulationInItsFilenameIsRetained() {
        val captures = temporary.newFolder("captures")
        val source = writeFits("SIMULATION-sky.fits", "LIGHT")
        val saved = requireNotNull(SolveFailureArchive.save(captures, source, "real failure"))
        assertArrayEquals(source.readBytes(), saved.image.readBytes())
    }

    @Test fun resolvingAnArchivedImageKeepsItsPathAndUpdatesOnlyItsLog() {
        val captures = temporary.newFolder("captures")
        val source = writeFits("sky.fits", "LIGHT")
        val first = requireNotNull(SolveFailureArchive.save(captures, source, "first failure", maxEntries = 1))
        val bytes = first.image.readBytes()
        val modified = first.image.lastModified()
        repeat(3) { index ->
            val retry = requireNotNull(SolveFailureArchive.save(captures, first.image, "retry $index", maxEntries = 1))
            assertEquals(first.image, retry.image)
            assertArrayEquals(bytes, first.image.readBytes())
            assertEquals(modified, first.image.lastModified())
            assertEquals("retry $index", first.log.readText())
            assertEquals(setOf(first.image, first.log), captures.walkTopDown().filter { it.isFile }.toSet())
        }
    }

    @Test fun cancelledLogReplacementPreservesThePreviousImageAndDiagnostics() {
        val captures = temporary.newFolder("captures")
        val source = writeFits("sky.fits", "LIGHT")
        val first = requireNotNull(SolveFailureArchive.save(captures, source, "original diagnostics"))
        var checks = 0
        try {
            SolveFailureArchive.save(captures, first.image, "new diagnostics", checkActive = {
                if (++checks == 2) throw CancellationException("Stopped")
            })
            fail("Cancellation ignored")
        } catch (_: CancellationException) { }
        assertArrayEquals(source.readBytes(), first.image.readBytes())
        assertEquals("original diagnostics", first.log.readText())
        assertEquals(setOf(first.image, first.log), captures.walkTopDown().filter { it.isFile }.toSet())
    }

    @Test fun similarlyNamedImageOutsideTheArchiveRemainsUntouched() {
        val captures = temporary.newFolder("captures")
        val source = writeFits("solve-failed-import.fits", "LIGHT")
        val originalLog = SolveFailureArchive.logFor(source).apply { writeText("imported diagnostics") }
        val saved = requireNotNull(SolveFailureArchive.save(captures, source, "new failure"))
        assertNotEquals(source, saved.image)
        assertArrayEquals(source.readBytes(), saved.image.readBytes())
        assertEquals("imported diagnostics", originalLog.readText())
    }

    private fun writeFits(name: String, imageType: String): File = temporary.newFile(name).also { file ->
        FITSWriter().write(file, FrameData(ByteArray(16) { 20 }, 4, 4, PixelFormat.MONO8, 1, 0),
            1f, 0f, imageType = imageType)
    }
}
