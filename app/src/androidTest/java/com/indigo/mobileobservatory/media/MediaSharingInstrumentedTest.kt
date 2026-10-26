package com.indigo.mobileobservatory.media

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSharingInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    @Before fun fixtures() { root = File(context.cacheDir, "media-share/qa-${UUID.randomUUID()}").apply { check(mkdirs()) } }
    @After fun cleanUp() { root.deleteRecursively() }

    private fun assertReadable(intent: Intent, files: List<File>) {
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(files.size, intent.clipData!!.itemCount)
        files.forEachIndexed { index, file ->
            val uri = intent.clipData!!.getItemAt(index).uri
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun originalFilesHaveMatchingMimeStreamsAndReadOnlyContentGrants() {
        listOf("jpg" to "image/jpeg", "PNG" to "image/png", "fits" to "application/octet-stream", "fit" to "application/octet-stream").forEach { (ext, mime) ->
            val file = File(root, "image.$ext").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val intent = mediaShareIntent(context, listOf(file))
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals(mime, intent.type)
            @Suppress("DEPRECATION") val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertEquals(stream, intent.clipData!!.getItemAt(0).uri)
            assertReadable(intent, listOf(file))
        }
    }

    @Test fun multipleFilesGrantEveryStreamAndRejectMissingFiles() {
        val files = listOf("image.png", "raw.fits").map { name -> File(root, name).apply { writeBytes(byteArrayOf(3, 2, 1)) } }
        val intent = mediaShareIntent(context, files)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("application/octet-stream", intent.type)
        @Suppress("DEPRECATION") val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(2, streams.size)
        streams.forEachIndexed { index, uri -> assertEquals(uri, intent.clipData!!.getItemAt(index).uri) }
        assertReadable(intent, files)
        assertThrows(IllegalArgumentException::class.java) { mediaShareIntent(context, listOf(File(root, "missing.png"))) }
    }

    @Test fun pngPreviewPreservesDisplayedPixelsAndOriginalScientificBytes() {
        val source = File(root, "original.fits").apply { writeBytes(byteArrayOf(1, 3, 5, 7)) }
        val original = source.readBytes()
        val display = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffa07040.toInt()) }
        val preview = writeMediaSharePreview(context, source, display)
        try {
            assertArrayEquals(original, source.readBytes())
            val decoded = BitmapFactory.decodeFile(preview.absolutePath)!!
            assertEquals(8, decoded.width); assertEquals(6, decoded.height)
            assertEquals(display.getPixel(2, 3), decoded.getPixel(2, 3))
            decoded.recycle()
            val intent = mediaShareIntent(context, listOf(preview))
            assertEquals("image/png", intent.type)
            assertReadable(intent, listOf(preview))
        } finally { preview.delete(); display.recycle() }
    }

    @Test fun cancelledPreviewRemovesIncompleteOutput() {
        val display = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val directory = File(context.cacheDir, "media-share")
        val prefix = "cancel-${UUID.randomUUID()}"
        var checks = 0
        try {
            assertThrows(CancellationException::class.java) {
                writeMediaSharePreview(context, File(root, "$prefix.fits"), display) {
                    if (++checks == 2) throw CancellationException()
                }
            }
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(prefix) })
        } finally { display.recycle() }
    }
}
