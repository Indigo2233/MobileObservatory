package com.indigo.mobileobservatory.ui

import android.system.Os
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.SERWriter
import com.indigo.mobileobservatory.stacking.LinearImage
import com.indigo.mobileobservatory.stacking.StackFitsWriter
import com.indigo.mobileobservatory.stacking.StackMode
import com.indigo.mobileobservatory.stacking.StackTaskPhase
import com.indigo.mobileobservatory.ui.screens.ObservatoryPageHost
import com.indigo.mobileobservatory.ui.screens.StackingScreen
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
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

    @Test fun playbackStackActionPausesAndReturnsToUsablePlayer() {
        val source = recording(900)
        val selected = AtomicReference<File?>()
        val openAtCallback = AtomicInteger(-1)
        val returned = AtomicInteger()
        val store = ViewModelStore()
        lateinit var vm: CameraViewModel
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[CameraViewModel::class.java]
        }
        try {
        var stackCovered by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box {
                    ObservatoryPageHost(!stackCovered) {
                        PlayerScreen(File(directory, "recordings"), File(directory, "captures"),
                            onStackFile = { file ->
                                openAtCallback.set(descriptorsFor(file))
                                selected.set(file)
                                vm.openPlanetaryStack(file)
                                stackCovered = true
                            }, onBack = { returned.incrementAndGet() })
                    }
                    if (stackCovered) ObservatoryPageHost(true) {
                        StackingScreen(vm, StackMode.PLANETARY, onBack = {
                            vm.stacking.leave()
                            stackCovered = false
                        })
                    }
                }
            }
        }
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
        assertTrue("A covered player's reader is retained for return", openAtCallback.get() > 0)
        compose.waitUntil(20_000) { vm.stacking.task.value.phase in setOf(StackTaskPhase.READY, StackTaskPhase.FAILED) }
        assertEquals(vm.stacking.task.value.message, StackTaskPhase.READY, vm.stacking.task.value.phase)
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription(context.getString(R.string.play))).fetchSemanticsNodes().isNotEmpty()
        }
        fun displayedFrame(): Int {
            val node = compose.onAllNodes(hasContentDescription(framePrefix, substring = true)).fetchSemanticsNodes().single()
            val description = node.config[SemanticsProperties.ContentDescription].single()
            return Regex("\\d+").find(description)!!.value.toInt()
        }
        val pausedFrame = displayedFrame()
        compose.onNodeWithContentDescription(context.getString(R.string.play)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.pause)).assertIsDisplayed()
        compose.waitUntil(10_000) { displayedFrame() > pausedFrame }
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.media_stack_action)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Leaving playback must still close its reader", 0, descriptorsFor(source))
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.runOnIdle { assertEquals(1, returned.get()) }
        assertTrue(source.isFile)
        } finally { instrumentation.runOnMainSync { store.clear() } }
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

    @Test fun fitsLp750HelpExplainsDisplayScopeAndCreditsTheAuthor() {
        val fitsDir = File(directory, "captures/FITS").apply { check(mkdirs()) }
        val file = File(fitsDir, "lp750-help.fits")
        StackFitsWriter.write(file, LinearImage(8, 8, 3, FloatArray(8 * 8 * 3) { .25f }))

        val openedUrl = AtomicReference<String?>()
        showPlayer(uriHandler = object : UriHandler {
            override fun openUri(uri: String) { openedUrl.set(uri) }
        }) {}
        compose.onNodeWithText(context.getString(R.string.media_category_fits)).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(file.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(file.name).performClick()
        compose.onNodeWithText("LP750").assertIsNotSelected()
        compose.onNodeWithContentDescription(context.getString(R.string.lp750_help_button))
            .assertIsDisplayed()
            .performClick()

        compose.onNodeWithText(context.getString(R.string.lp750_help_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lp750_help_description)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lp750_credit)).assertIsDisplayed()
        assertTrue(context.getString(R.string.lp750_credit).contains("奎木娘HIP5447"))
        compose.onNodeWithText("https://space.bilibili.com/455218677")
            .assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals("https://space.bilibili.com/455218677", openedUrl.get()) }
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        compose.onNodeWithText(context.getString(R.string.lp750_help_title)).assertDoesNotExist()
        compose.onNodeWithText("LP750").assertIsNotSelected()
    }

    private fun showPlayer(uriHandler: UriHandler? = null, onStack: (File) -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides (uriHandler ?: LocalUriHandler.current)) {
                MaterialTheme {
                    PlayerScreen(recordingsDir = File(directory, "recordings"), capturesDir = File(directory, "captures"),
                        onStackFile = onStack, onBack = {})
                }
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
