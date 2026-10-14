package com.indigo.mobileobservatory.media

import android.app.Application
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.recording.MediaCategory
import com.indigo.mobileobservatory.recording.MediaLibrary
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LibraryImportInstrumentedTest {
    @Test fun contentImportAppearsInTheSharedLibraryWithoutChangingTheOriginalOrStartingStacking() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val store = ViewModelStore()
        lateinit var importer: LibraryImportViewModel
        val token = "library-import-test-${UUID.randomUUID()}"
        val source = File(app.cacheDir, "updates/$token.png").also { it.parentFile!!.mkdirs() }
        var imported: File? = null
        try {
            val image = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
            image.eraseColor(0xff4582b0.toInt())
            source.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
            val bytes = source.readBytes()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", source)
            instrumentation.runOnMainSync {
                importer = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[LibraryImportViewModel::class.java]
                importer.importFile(uri)
            }
            withTimeout(10_000) { while (importer.state.value.busy) delay(20) }
            assertNull(importer.state.value.error)
            assertEquals(1L, importer.state.value.revision)
            imported = MediaLibrary.list(app.getExternalFilesDir("recordings"), app.getExternalFilesDir("captures"),
                MediaCategory.RECENT).single { it.name.startsWith(token) }.file
            assertNotEquals(source.canonicalPath, imported!!.canonicalPath)
            assertArrayEquals(bytes, source.readBytes())
            assertArrayEquals(bytes, imported!!.readBytes())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            source.delete(); imported?.delete()
        }
    }
}
