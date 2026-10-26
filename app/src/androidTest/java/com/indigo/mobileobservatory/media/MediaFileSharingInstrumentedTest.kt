package com.indigo.mobileobservatory.media

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.FITSWriter
import com.indigo.mobileobservatory.update.UpdateInstaller
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@Suppress("DEPRECATION")
class MediaFileSharingInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<File>()

    @After fun cleanup() { fixtures.forEach { it.delete() } }

    @Test fun fitsSharesAsAFileAtEveryMimeBoundaryAndRetainsItsOriginalBytesAndName() {
        for (extension in listOf("fit", "fits", "FIT", "FITS")) {
            val file = fixture(extension)
            FITSWriter().write(file, FrameData(ByteArray(32 * 24 * 2) { (it * 17).toByte() },
                32, 24, PixelFormat.MONO16, 1, 0), 1f, 0f)
            val original = file.readBytes()
            val intent = mediaShareIntent(context, listOf(file))
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("application/octet-stream", intent.type)
            assertEquals(listOf("application/octet-stream"), clipTypes(intent))
            val uri = requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertEquals("application/octet-stream", context.contentResolver.getType(uri))
            assertEquals(file.name, displayName(uri))
            assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertArrayEquals(original, file.readBytes())
            assertReadGrantOnly(intent)
        }
    }

    @Test fun multipleFitsAndMixedPhotoSelectionsUseFileSharingRegardlessOfOrder() {
        val fit = fixture("fit"); val fits = fixture("FITS"); val photo = fixture("jpg")
        for (files in listOf(listOf(fit, fits), listOf(photo, fit), listOf(fit, photo))) {
            val intent = mediaShareIntent(context, files)
            assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
            assertEquals("application/octet-stream", intent.type)
            assertEquals(listOf("application/octet-stream"), clipTypes(intent))
            val uris = requireNotNull(intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals(files.map { it.name }, uris.map(::displayName))
            assertEquals(uris, (0 until intent.clipData!!.itemCount).map { intent.clipData!!.getItemAt(it).uri })
            for ((file, uri) in files.zip(uris)) {
                assertEquals(if (file.extension.equals("jpg", true)) "image/jpeg" else "application/octet-stream",
                    context.contentResolver.getType(uri))
                assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            }
            assertReadGrantOnly(intent)
        }
    }

    @Test fun photosVideosArchivesLogsAndTheApkInstallerKeepTheirExistingTypes() {
        for ((extension, expected) in listOf("jpg" to "image/jpeg", "png" to "image/png",
                "mp4" to "video/mp4", "zip" to "application/zip", "txt" to "text/plain",
                "json" to "application/json", "apk" to UpdateInstaller.APK_MIME_TYPE)) {
            val file = fixture(extension)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            assertEquals(expected, context.contentResolver.getType(uri))
            if (extension in listOf("jpg", "png", "mp4", "zip")) {
                val intent = mediaShareIntent(context, listOf(file))
                assertEquals(expected, intent.type)
                assertEquals(listOf(expected), clipTypes(intent))
            } else if (extension == "apk") {
                val intent = UpdateInstaller.installIntent(context, file)
                assertEquals(expected, intent.type)
                assertEquals(uri, intent.data)
            }
        }
    }

    private fun fixture(extension: String): File = File(context.cacheDir, "media-share/分享-${UUID.randomUUID()}.$extension")
        .also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(3, 1, 4, 1, 5, 9)); fixtures += it }

    private fun displayName(uri: Uri): String = context.contentResolver.query(uri,
        arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
        assertTrue(it.moveToFirst()); it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
    }

    private fun clipTypes(intent: Intent) = intent.clipData!!.description.let { description ->
        (0 until description.mimeTypeCount).map(description::getMimeType)
    }

    private fun assertReadGrantOnly(intent: Intent) {
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}
