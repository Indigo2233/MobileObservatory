package com.indigo.mobileobservatory.pointing

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Real VM entry points with a fake camera; never connect or command physical hardware. */
class PointingReferencePreflightInstrumentedTest {
    private class TestApplication(base: Context) : Application() {
        private val prefix = "reference-preflight-${System.nanoTime()}-"
        val isolatedFiles = File(base.filesDir, prefix).also { it.mkdirs() }
        init { attachBaseContext(base) }
        override fun getFilesDir(): File = isolatedFiles
        override fun getExternalFilesDir(type: String?): File = File(isolatedFiles, "external/${type ?: "root"}").also { it.mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
    }
    private class FakeCamera : Camera {
        override val isOpen = MutableStateFlow(true)
        override val isCapturing = MutableStateFlow(true)
        override val cameraInfo = CameraInfo("WF fake", "preflight", 1024, 768, 16, pixelSizeUm = 3.75f)
        override val exposureRange = FloatRange(1000f, 30_000_000f, 1_000_000f)
        override val gainRange = FloatRange(0f, 100f, 0f)
        override val currentExposureUs = 1_000_000f
        override val currentGain = 0f
        override val currentPixelFormat = PixelFormat.MONO16
        override val supportedPixelFormats = listOf(PixelFormat.MONO16)
        override val currentRoi = Roi(0, 0, 1024, 768)
        override val cropInfo = CropInfo(0, 0, 1024, 768)
        override val hwExposureMaxUs = 30_000_000f
        override var longExposureEnabled = false
        override val sciencePixelTraits = PixelTraits(validBits = 16)
        override val scienceCaptureGeneration = 7L
        var starts = 0
        var stops = 0
        override fun close() { isOpen.value = false; isCapturing.value = false }
        override fun startCapture(callback: FrameCallback) { starts++; isCapturing.value = true }
        override fun stopCapture() { stops++; isCapturing.value = false }
        override fun setExposureTime(us: Float) = Unit
        override fun setGain(value: Float) = Unit
        override fun setPixelFormat(format: PixelFormat) = Unit
        override fun setRoi(roi: Roi) = Unit
        override fun resetRoi() = Unit
        override fun recycleBuffer(buf: ByteArray) = Unit
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: TestApplication
    private lateinit var store: ViewModelStore
    private lateinit var vm: CameraViewModel
    private lateinit var camera: FakeCamera

    @Before fun createVm() {
        app = TestApplication(instrumentation.targetContext.applicationContext)
        app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit().putBoolean("update_auto_check", false).commit()
        store = ViewModelStore()
        camera = FakeCamera()
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
            vm.cameraManager.javaClass.getDeclaredField("activeCamera").apply { isAccessible = true }.set(vm.cameraManager, camera)
        }
    }
    @After fun clearVm() {
        instrumentation.runOnMainSync { store.clear() }
        app.isolatedFiles.deleteRecursively()
    }

    @Test fun missingDatabaseRejectsAutomaticMotionAndDirectReferenceUsingTheSamePrecondition() = runBlocking {
        assertEarlyRejection(R.string.precision_goto_need_d50)
    }
    @Test fun pausedPreviewRejectsAutomaticMotionBeforeLookingForSiteOrSendingGoto() = runBlocking {
        // Presence-only fixture: no test executes ASTAP or claims this is a real star database.
        File(app.filesDir, "astap").also { it.mkdirs() }.resolve("d50_preflight_fixture.1476").writeBytes(byteArrayOf(0))
        val field = vm.javaClass.getDeclaredField("_previewPaused").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val paused = field.get(vm) as MutableStateFlow<Boolean>
        paused.value = true
        assertEarlyRejection(R.string.wfp_reference_preview)
    }

    private suspend fun assertEarlyRejection(message: Int) {
        var samples = 0
        val automatic = runCatching {
            vm.runPointingCalibrationMoves(listOf(PhoneCatalogStar(10.0, 20.0, 1.0, 1, "first"),
                PhoneCatalogStar(30.0, 25.0, 1.0, 2, "second"))) { samples++ }
        }.exceptionOrNull()
        assertTrue(automatic is IllegalStateException)
        assertEquals(app.getString(message), automatic?.message)
        val direct = runCatching { vm.capturePointingReference() }.exceptionOrNull()
        assertTrue(direct is IllegalStateException)
        assertEquals(app.getString(message), direct?.message)
        assertEquals(0, samples)
        assertEquals(0, camera.starts)
        assertEquals(0, camera.stops)
        assertFalse(vm.mountMotionState.value.isActive)
    }
}
