package com.indigo.mobileobservatory.recording

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaLibraryTest {
    private fun withLibrary(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("media-library").toFile()
        try { test(File(root, "recordings").apply { mkdirs() }, File(root, "captures").apply { mkdirs() }) }
        finally { root.deleteRecursively() }
    }
    private fun file(root: File, path: String, time: Long, count: Int = 4): File = File(root, path).apply {
        parentFile!!.mkdirs(); writeBytes(ByteArray(count)); check(setLastModified(time))
    }

    @Test fun stackResultsFindOnlyFinishedImagesAcrossBothExportFolders() = withLibrary { recordings, captures ->
        file(captures, "JPG/stack-live-one.png", 1000)
        file(captures, "FITS/stack-live-one-linear.fits", 3000)
        file(captures, "JPG/stack-live-one.json", 5000)
        file(captures, "JPG/stack-live-two.png.partial", 6000)
        file(captures, "JPG/stack-live-three.partial.png", 7000)
        file(captures, "JPG/.stack-live-four.png", 8000)
        file(captures, "JPG/moon.jpg", 9000)
        file(captures, "FITS/raw.fits", 10000)
        val results = MediaLibrary.list(recordings, captures, MediaCategory.STACK_RESULTS)
        assertEquals(listOf("stack-live-one-linear.fits", "stack-live-one.png"), results.map { it.name })
        assertEquals(listOf("FITS", "PNG"), results.map { it.format })
        assertEquals(2, MediaLibrary.list(recordings, captures, MediaCategory.JPG).size)
    }

    @Test fun recordingsMergeSerPserSortNewestAndExcludeActiveFile() = withLibrary { recordings, captures ->
        file(recordings, "SER/old.ser", 1000)
        file(recordings, "PSER/new.PSER", 3000)
        val active = file(recordings, "SER/active.ser", 6000)
        file(recordings, "SER/.hidden.ser", 9000)
        file(recordings, "SER/broken.partial.ser", 10000)
        file(recordings, "MP4/movie.mp4", 11000)
        val results = MediaLibrary.list(recordings, captures, MediaCategory.SER_PSER, active)
        assertEquals(listOf("new.PSER", "old.ser"), results.map { it.name })
        assertTrue(active.exists())
        assertEquals(1, MediaLibrary.list(recordings, captures, MediaCategory.MP4).size)
    }

    @Test fun fitsSequencesStillAppearAsOneEntryWithValidFrameSizes() = withLibrary { recordings, captures ->
        file(recordings, "FITS/session/01.fits", 1000, 8)
        val excluded = file(recordings, "FITS/session/02.fit", 2000, 16)
        file(recordings, "FITS/session/.03.fits", 3000, 32)
        file(recordings, "FITS/session/04.partial.fits", 4000, 64)
        file(captures, "FITS/single.fits", 5000)
        val results = MediaLibrary.list(recordings, captures, MediaCategory.FITS, excluded)
        assertEquals(2, results.size)
        assertEquals(8L, results.single { it.name == "session" }.size)
        assertEquals("FITS", results.single { it.name == "session" }.format)
    }

    @Test fun missingDirectoriesAndDirectOpenTargetsAreHandled() = withLibrary { recordings, captures ->
        assertTrue(MediaLibrary.list(null, null, MediaCategory.STACK_RESULTS).isEmpty())
        assertTrue(MediaLibrary.list(recordings, captures, MediaCategory.SER_PSER).isEmpty())
        assertNull(MediaLibrary.describe(File(captures, "missing.png")))
        assertNull(MediaLibrary.describe(file(captures, "JPG/stack-x.json", 1)))
        val png = file(captures, "JPG/stack-x.png", 2, 23)
        assertEquals(VideoFileInfo(png, png.name, 23, "PNG"), MediaLibrary.describe(png))
    }

    @Test fun imageSelectionIncludesIndividualFitsSequenceFramesAndPhotos() = withLibrary { recordings, captures ->
        val first = file(recordings, "FITS/night/01.fits", 3000)
        val second = file(recordings, "FITS/night/02.FIT", 6000)
        val active = file(recordings, "FITS/night/03.fit", 10000)
        val photo = file(captures, "JPG/moon.png", 7000)
        val single = file(captures, "FITS/single.fits", 5000)
        file(captures, "JPG/moon.json", 11000)
        file(recordings, "FITS/night/.hidden.fits", 12000)
        file(recordings, "FITS/night/unfinished.partial.fit", 13000)
        file(recordings, "FITS/night/not-a-frame.png", 14000)
        file(recordings, "MP4/movie.mp4", 15000)
        val images = MediaLibrary.list(recordings, captures, MediaCategory.PHOTOS, active, expandFitsFolders = true)
        assertEquals(listOf(photo, second, single, first), images.map { it.file })
        assertTrue(images.all { it.file.isFile })
        assertTrue(MediaLibrary.list(recordings, captures, MediaCategory.PHOTOS, active).any { it.file.name == "night" && it.file.isDirectory })
        assertTrue(active.exists())
    }

    @Test fun expandedFitsSelectionKeepsFormatsExclusionsAndDuplicateRootsConsistent() = withLibrary { _, captures ->
        val fits = file(captures, "FITS/session/one.FITS", 1000)
        val excluded = file(captures, "FITS/session/two.fit", 2000)
        file(captures, "JPG/photo.jpg", 3000)
        val images = MediaLibrary.list(captures, captures, MediaCategory.FITS, excluded, expandFitsFolders = true)
        assertEquals(listOf(fits), images.map { it.file })
        assertTrue(MediaLibrary.list(null, null, MediaCategory.PHOTOS, expandFitsFolders = true).isEmpty())
    }

    @Test fun recentMergesPhotosRawFramesResultsAndVideosWithoutActiveOrPartialFiles() = withLibrary { recordings, captures ->
        file(captures, "JPG/moon.jpg", 1000)
        file(captures, "FITS/raw.fits", 3000)
        file(captures, "JPG/stack-M42.png", 5000)
        file(recordings, "PSER/jupiter.pser", 4000)
        file(recordings, "MP4/moon.mp4", 2000)
        val active = file(recordings, "SER/active.ser", 9000)
        file(captures, "JPG/.hidden.png", 10000)
        file(captures, "JPG/broken.partial.png", 11000)
        assertEquals(listOf("stack-M42.png", "jupiter.pser", "raw.fits", "moon.mp4", "moon.jpg"),
            MediaLibrary.list(recordings, captures, MediaCategory.RECENT, active).map { it.name })
        assertEquals(listOf("jupiter.pser", "moon.mp4"),
            MediaLibrary.list(recordings, captures, MediaCategory.VIDEOS, active).map { it.name })
        assertEquals(listOf("stack-M42.png", "raw.fits", "moon.jpg"),
            MediaLibrary.list(recordings, captures, MediaCategory.PHOTOS, active).map { it.name })
    }
}
