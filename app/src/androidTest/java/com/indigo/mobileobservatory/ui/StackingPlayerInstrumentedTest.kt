package com.indigo.mobileobservatory.ui

import android.system.Os
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.SERWriter
import com.indigo.mobileobservatory.stacking.LinearImage
import com.indigo.mobileobservatory.stacking.StackFitsWriter
import com.indigo.mobileobservatory.ui.screens.PlayerScreen
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StackingPlayerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File

    @Before fun createFixtureDirectory() {
        directory = File(context.cacheDir, "stack-player-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
    }

    @After fun removeFixtures() { if (::directory.isInitialized) directory.deleteRecursively() }

    @Test fun serFileListOffersStackActionAndPreservesSource() {
        val source = recording(2)
        val selected = AtomicReference<File?>()
        showPlayer { selected.set(it) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(source.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(source.name).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.stack_process_file)).performClick()
        compose.waitUntil(10_000) { selected.get() != null }
        assertEquals(source.canonicalPath, selected.get()!!.canonicalPath)
        assertTrue(source.isFile)
        assertEquals(0, descriptorsFor(source))
    }

    @Test fun playbackStackActionWaitsForReaderClosureBeforeCallback() {
        val source = recording(180)
        val selected = AtomicReference<File?>()
        val openAtCallback = AtomicInteger(-1)
        showPlayer { file -> openAtCallback.set(descriptorsFor(file)); selected.set(file) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(source.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(source.name).performClick()
        val framePrefix = context.getString(R.string.frame_desc, 0).substringBefore('0')
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription(framePrefix, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodes(hasContentDescription(context.getString(R.string.play))).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription(context.getString(R.string.play)).performClick()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.pause)).assertIsDisplayed()
        assertTrue("The video was actually open before navigation", descriptorsFor(source) > 0)
        compose.onNodeWithContentDescription(context.getString(R.string.stack_planetary)).assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { selected.get() != null }
        assertEquals(source.canonicalPath, selected.get()!!.canonicalPath)
        assertEquals("No frame/timestamp reader may remain open when stacking starts", 0, openAtCallback.get())
        assertTrue(source.isFile)
    }

    @Test fun normalizedFloatFitsReopensAsVisibleImageAndShowsEmbeddedMetadata() {
        try {
        val fitsDir = File(directory, "captures/FITS").apply { check(mkdirs()) }
        val file = File(fitsDir, "precision-stack.fits")
        val source = LinearImage(64, 48, 3, FloatArray(64 * 48 * 3) { i ->
            val pixel = i / 3
            when (i % 3) {
                0 -> .01f + (pixel % 64) / 150f
                1 -> .02f + (pixel / 64) / 130f
                else -> .04f + (pixel % 13) / 90f
            }
        })
        StackFitsWriter.write(file, source, mapOf("source" to "precision-proof.ser", "frameCount" to "23", "calibrated" to "false"))
        showPlayer {}
        compose.onNodeWithText(context.getString(R.string.media_category_fits)).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(file.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(file.name).performClick()
        android.util.Log.i("StackFitsUiTest", "opened file")
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription(context.getString(R.string.image_desc))).fetchSemanticsNodes().isNotEmpty()
        }
        val rendered = compose.onNodeWithContentDescription(context.getString(R.string.image_desc)).assertIsDisplayed().captureToImage()
        android.util.Log.i("StackFitsUiTest", "image visible")
        val pixels = rendered.toPixelMap()
        val colors = mutableSetOf<Int>()
        for (y in 0 until rendered.height step maxOf(1, rendered.height / 32)) {
            for (x in 0 until rendered.width step maxOf(1, rendered.width / 32)) colors += pixels[x, y].toArgb()
        }
        assertTrue("Normalized float samples must not collapse to a uniform black bitmap", colors.size > 20)
        compose.onNodeWithContentDescription(context.getString(R.string.section_info)).performClick()
        compose.onNodeWithText("precision-proof.ser", substring = true).assertIsDisplayed()
        android.util.Log.i("StackFitsUiTest", "metadata visible")
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        compose.onNodeWithText("precision-proof.ser", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(file.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.image_desc)).assertDoesNotExist()
        compose.waitForIdle()
        android.util.Log.i("StackFitsUiTest", "back in library and idle")
        assertTrue(file.isFile)
        } catch (failure: Throwable) {
            android.util.Log.e("StackFitsUiTest", "Test body failed before teardown", failure)
            throw failure
        }
    }

    private fun showPlayer(onStack: (File) -> Unit) {
        compose.setContent {
            MaterialTheme {
                PlayerScreen(recordingsDir = File(directory, "recordings"), capturesDir = File(directory, "captures"),
                    onStackFile = onStack, onBack = {})
            }
        }
    }

    private fun recording(count: Int): File {
        val parent = File(directory, "recordings/SER").apply { check(mkdirs()) }
        val file = File(parent, "real-recording.ser")
        val writer = SERWriter(file)
        writer.open(48, 32, PixelFormat.MONO8)
        try {
            repeat(count) { index ->
                writer.writeFrame(FrameData(ByteArray(48 * 32) { ((it + index) % 239).toByte() },
                    48, 32, PixelFormat.MONO8, index.toLong(), index.toLong()))
            }
        } finally { writer.close() }
        // Stable 30 Hz playback timing rather than wall-clock speed of fixture generation.
        RandomAccessFile(file, "rw").use { output ->
            output.seek(178L + 48L * 32L * count)
            repeat(count) { index -> output.writeLong(java.lang.Long.reverseBytes(638_000_000_000_000_000L + index * 333_333L)) }
        }
        return file
    }

    /** Linux procfs observes the real process file handles, independently of SERReader state. */
    private fun descriptorsFor(file: File): Int = File("/proc/self/fd").listFiles().orEmpty().count { descriptor ->
        runCatching { File(Os.readlink(descriptor.absolutePath)).canonicalPath == file.canonicalPath }.getOrDefault(false)
    }
}
