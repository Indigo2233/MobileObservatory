package com.indigo.mobileobservatory.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.recording.MediaCategory
import com.indigo.mobileobservatory.recording.MediaLibrary
import com.indigo.mobileobservatory.ui.screens.PlayerScreen
import com.indigo.mobileobservatory.ui.screens.mediaVideoViewIntent
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private var externalVideo: File? = null

    @Before fun fixtures() { root = File(context.cacheDir, "media-library-${UUID.randomUUID()}").apply { check(mkdirs()) } }
    @After fun cleanUp() { root.deleteRecursively(); externalVideo?.delete() }

    @Test fun exportedPngOpensDirectlyThenReturnsToCallingTask() {
        val result = File(root, "captures/JPG/stack-live-fixture.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xffa07040.toInt())
        result.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        File(result.parentFile, "ordinary.jpg").writeBytes(byteArrayOf(1))
        File(result.parentFile, "stack-live-fixture.json").writeText("{}")
        val returned = AtomicInteger()
        compose.setContent { MaterialTheme {
            PlayerScreen(File(root, "recordings"), File(root, "captures"),
                initialFile = result, initialCategory = MediaCategory.STACK_RESULTS, onBack = { returned.incrementAndGet() })
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.image_desc)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.image_desc)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.runOnIdle { assertEquals(1, returned.get()) }
    }

    @Test fun resultsLibraryFindsExportWithoutShowingOrdinaryImagesOrSidecars() {
        val result = File(root, "captures/JPG/stack-live-fixture.png").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1))
        }
        File(result.parentFile, "ordinary.jpg").writeBytes(byteArrayOf(1))
        File(result.parentFile, "stack-live-fixture.json").writeText("{}")
        compose.setContent { MaterialTheme {
            PlayerScreen(File(root, "recordings"), File(root, "captures"),
                initialCategory = MediaCategory.STACK_RESULTS, onBack = {})
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(result.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.media_category_results)).assertIsSelected()
        compose.onNodeWithText(result.name).assertIsDisplayed()
        compose.onNodeWithText("ordinary.jpg").assertDoesNotExist()
        compose.onNodeWithText("stack-live-fixture.json").assertDoesNotExist()
    }

    @Test fun fileListExcludesActiveRecordingAndOffersVisibleStackAction() {
        val folder = File(root, "recordings/SER").apply { mkdirs() }
        val ready = File(folder, "finished.ser").apply { writeBytes(byteArrayOf(1)) }
        val active = File(folder, "recording-now.ser").apply { writeBytes(byteArrayOf(2)) }
        val selected = AtomicReference<File?>()
        assertEquals(listOf(ready.name), MediaLibrary.list(File(root, "recordings"), File(root, "captures"), MediaCategory.SER_PSER, active).map { it.name })
        compose.setContent { MaterialTheme {
            PlayerScreen(File(root, "recordings"), File(root, "captures"), onStackFile = { selected.set(it) },
                excludeFile = active, onBack = {})
        } }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(ready.name).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            println("Media fixture exists=${ready.exists()}; index=${MediaLibrary.list(File(root, "recordings"), File(root, "captures"), MediaCategory.SER_PSER, active).map { it.name }}")
            println(compose.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
        compose.onNodeWithText(active.name).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.media_stack_action)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.stack_process_file)).performClick()
        assertEquals(ready.absolutePath, selected.get()?.absolutePath)
        assertTrue(active.isFile)
    }

    @Test fun mp4UsesProviderContentUriWithReadOnlyGrantAndReadableOriginalBytes() {
        val video = File(context.getExternalFilesDir("recordings"), "qa-${UUID.randomUUID()}.mp4")
        externalVideo = video
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        video.writeBytes(bytes)
        val intent = mediaVideoViewIntent(context, video)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("video/mp4", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.fileprovider", intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val readBack = context.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() }
        assertArrayEquals(bytes, readBack)
    }
}
