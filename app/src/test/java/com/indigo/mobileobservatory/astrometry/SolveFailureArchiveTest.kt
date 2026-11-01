package com.indigo.mobileobservatory.astrometry

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
        val saved = SolveFailureArchive.save(captures, source, log)
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
        val first = SolveFailureArchive.save(captures, source, "first", maxEntries = 2)
        assertTrue(first.image.setLastModified(1_000))
        val jpeg = temporary.newFile("source.jpg").apply { writeText("jpeg") }
        val second = SolveFailureArchive.save(captures, jpeg, "second", maxEntries = 2)
        assertTrue(second.image.setLastModified(2_000))
        val regular = File(first.image.parentFile, "user-image.fits").apply { writeText("original") }
        val unrelated = File(first.image.parentFile, "solve-failed-user.fits").apply { writeText("original") }
        val third = SolveFailureArchive.save(captures, source, "third", maxEntries = 2)
        assertFalse(first.image.exists()); assertFalse(first.log.exists())
        assertTrue(second.image.exists()); assertTrue(second.log.exists())
        assertTrue(third.image.exists()); assertTrue(third.log.exists())
        assertEquals("original", regular.readText()); assertEquals("original", unrelated.readText())
    }

    @Test fun byteLimitIncludesBothTheImageAndLog() {
        val captures = temporary.newFolder("captures")
        val source = temporary.newFile("source.fits").apply { writeBytes(ByteArray(60)) }
        val first = SolveFailureArchive.save(captures, source, "log".repeat(10), maxBytes = 150)
        val second = SolveFailureArchive.save(captures, source, "log".repeat(10), maxBytes = 150)
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
}
