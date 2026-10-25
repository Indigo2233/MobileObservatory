package com.indigo.mobileobservatory.camera

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.stacking.LiveStackPhase
import com.indigo.mobileobservatory.stacking.PixelTraits
import com.indigo.mobileobservatory.stacking.SampleAlignment
import com.indigo.mobileobservatory.stacking.SerCfaInterpretation
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.polar.PolarSampleSession
import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual VM callback fanout, writers and preview; the fake SDK supplies linear sensor words. */
@RunWith(AndroidJUnit4::class)
class CameraCfaPipelineInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun mp4IsFinalizedBeforeTheCameraAcceptsADifferentPixelFormat() = runBlocking {
        workspace(tokens = true) { vm, camera ->
            await { camera.emitLatest(); vm.scienceCaptureReady.value }
            var recording: File? = null
            try {
                main { vm.setRecordFormat("MP4"); vm.startRecording() }
                assertTrue(vm.isRecording.value)
                recording = checkNotNull(vm.getActiveRecordingFile())
                camera.emitLatest()
                await { vm.recordingFrameCount.value > 0 }
                main { vm.setPixelFormat(PixelFormat.MONO8) }
                assertFalse(vm.isRecording.value)
                await { camera.pixelFormatWrites == 1 }
                assertNull("SDK layout write must wait until the old recording has closed", vm.getActiveRecordingFile())
                assertFalse(vm.recordingFinalizing.value)
                assertEquals(PixelFormat.MONO8, camera.currentPixelFormat)
                assertTrue(requireNotNull(recording).length() > 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val name = requireNotNull(recording).name
                    await {
                        app.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.IS_PENDING} = 0",
                            arrayOf(name), null)?.use { it.moveToFirst() } == true
                    }
                    app.contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name))
                }
            } finally {
                main { vm.stopRecording() }
                await { vm.getActiveRecordingFile() == null }
                recording?.delete()
            }
        }
    }

    @Test fun cfaApplyReachesLiveKeyAndSerAndRecordingCannotChangeIt() = runBlocking {
        workspace(tokens = true) { vm, camera ->
            await { camera.emitLatest(); vm.scienceCaptureReady.value }
            main { assertTrue(vm.applyCameraCfa(SerCfaInterpretation.BGGR)) }
            await { camera.starts == 2 && !vm.cameraCfaApplying.value }
            await { camera.emitLatest(); vm.scienceCaptureReady.value }
            main { vm.startLiveStack() }
            assertEquals(LiveStackPhase.RUNNING, vm.stacking.live.state.value.phase)
            val key = vm.stacking.live.state.value.key!!
            assertEquals(PixelFormat.BAYER_BG12, key.format)
            assertEquals(12, key.pixelTraits.validBits)
            assertEquals(SampleAlignment.LEFT, key.pixelTraits.alignment)
            assertTrue(key.pixelTraits.bayerPatternAtOutput)
            val old = camera.emit(0, run = null, white = true)
            assertEquals(1, camera.releases[old]?.get())
            assertEquals(0L, vm.stacking.live.state.value.received)
            assertEquals(LiveStackPhase.RUNNING, vm.stacking.live.state.value.phase)
            var recording: File? = null
            try {
                main { vm.setRecordFormat("SER"); vm.startRecording() }
                assertTrue(vm.isRecording.value)
                recording = checkNotNull(vm.getActiveRecordingFile())
                main { assertFalse(vm.applyCameraCfa(SerCfaInterpretation.AUTO, resetLiveStack = true)) }
                assertEquals(2, camera.starts)
                assertEquals(SerCfaInterpretation.BGGR, vm.cameraCfa.value)
                camera.emitLatest()
                main { vm.stopRecording() }
                await { vm.getActiveRecordingFile() == null }
                SERReader(checkNotNull(recording)).use { reader ->
                    assertTrue(reader.open())
                    assertEquals(11, reader.header!!.colorId); assertEquals(12, reader.header!!.pixelDepth)
                    val image = reader.readLinearFrame(0)!!
                    val p = (12 * image.width + 12) * 3
                    assertEquals(.75, image.pixels[p].toDouble(), 1e-3)
                    assertEquals(.4, image.pixels[p + 1].toDouble(), 1e-3)
                    assertEquals(.15, image.pixels[p + 2].toDouble(), 1e-3)
                }
                main { assertFalse(vm.applyCameraCfa(SerCfaInterpretation.AUTO)); assertTrue(vm.applyCameraCfa(SerCfaInterpretation.AUTO, true)) }
                await { camera.starts == 3 && !vm.cameraCfaApplying.value }
                assertEquals(LiveStackPhase.EMPTY, vm.stacking.live.state.value.phase)
                assertEquals(0, camera.pixelFormatWrites)
                assertEquals(PixelFormat.MONO12, camera.currentPixelFormat)
            } finally {
                main { vm.stopRecording() }
                recording?.let { file -> file.delete(); File(file.parentFile, "${file.name}.capture.json").delete() }
            }
        }
    }

    @Test fun nullSdkTokensStillRejectOldCallbackAndRelabeledBuffersAreRecycledExactlyOnce() = runBlocking {
        workspace(tokens = false) { vm, camera ->
            main { assertTrue(vm.applyCameraCfa(SerCfaInterpretation.BGGR)) }
            await { camera.starts == 2 && !vm.cameraCfaApplying.value }
            val fresh = camera.emitLatest()
            await { vm.previewBitmap.value != null && camera.releases[fresh]?.get() == 1 }
            val color = vm.previewBitmap.value!!.getPixel(12, 12)
            assertTrue(Color.red(color) > Color.blue(color) + 40)
            val old = camera.emit(0, run = null, white = true)
            assertEquals(1, camera.releases[old]?.get())
            assertEquals(1, camera.releases[fresh]?.get())
            assertEquals(color, vm.previewBitmap.value!!.getPixel(12, 12))
            assertFalse(vm.scienceCaptureReady.value)
            assertEquals("wait_frame", vm.liveStackStartIssue())
            assertEquals(0, camera.pixelFormatWrites)
        }
    }

    @Test fun ownedJpegAndFitsSnapshotsUseFrameTimeCfaAndSettingsCannotChangeWhileWaiting() = runBlocking {
        workspace(tokens = true) { vm, camera ->
            main { assertTrue(vm.applyCameraCfa(SerCfaInterpretation.BGGR)) }
            await { camera.starts == 2 && !vm.cameraCfaApplying.value }
            val pending = vm.javaClass.getDeclaredField("pendingFrameSnapshot").apply { isAccessible = true }
            fun awaitingFrame() = (pending.get(vm) as AtomicReference<*>).get() != null
            val fitsTask = async(Dispatchers.IO) { vm.captureTempFitsForPlateSolve() }
            await { camera.singleStarts == 1 }
            main { assertFalse(vm.applyCameraCfa(SerCfaInterpretation.RGGB)) }
            // The polar sample must belong to an exposure started after the request.
            kotlinx.coroutines.delay((camera.currentExposureUs / 1000).toLong() + 20)
            camera.emitLatest()
            val fits = checkNotNull(fitsTask.await())
            try {
                assertFalse("Polar acquisition must stop before solving or mount movement", camera.isCapturing.value)
                assertTrue("Polar acquisition must leave preview paused", vm.previewPaused.value)
                val header = fits.readBytes().copyOfRange(0, 2880).toString(Charsets.US_ASCII)
                assertTrue(header.contains("BAYERPAT= 'BGGR'")); assertTrue(Regex("VALIDBIT=\\s+12").containsMatchIn(header))
                val actualStart = java.time.Instant.parse(com.indigo.mobileobservatory.util.FitsHeaderParser.read(fits)["DATE-OBS"])
                assertEquals(camera.lastFrameTimestamp - (camera.currentExposureUs / 1000).toLong(), actualStart.toEpochMilli())
            } finally { fits.delete() }
            main { vm.togglePreviewPause() }
            await { camera.isCapturing.value }
            val jpegDirectory = File(app.getExternalFilesDir("captures"), "JPG")
            val before = jpegDirectory.listFiles()?.map { it.absolutePath }?.toSet().orEmpty()
            var jpeg: File? = null
            try {
                main { vm.setCaptureFormat("JPG"); vm.capture() }
                await(::awaitingFrame)
                camera.emitLatest()
                await {
                    jpeg = jpegDirectory.listFiles()?.firstOrNull { it.absolutePath !in before && it.name.contains(camera.cameraInfo.name) }
                    jpeg?.isFile == true && vm.statusMessage.value.contains(jpeg!!.name)
                }
                val bitmap = checkNotNull(BitmapFactory.decodeFile(jpeg!!.absolutePath))
                try { assertTrue(Color.red(bitmap.getPixel(12,12)) > Color.blue(bitmap.getPixel(12,12)) + 40) }
                finally { bitmap.recycle() }
            } finally {
                jpeg?.let { file ->
                    // Only this fixture's newly created gallery item and app-owned file.
                    app.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(file.name))
                    file.delete()
                }
            }
        }
    }

    @Test fun polarSamplesKeepTheCameraIdleThroughoutSolveAndBetweenPoints() = runBlocking {
        workspace(tokens = true) { vm, camera ->
            val session = PolarSampleSession()
            val outputs = mutableListOf<File>()
            try {
                repeat(3) { point ->
                    val enteredSolve = CompletableDeferred<Unit>()
                    val finishSolve = CompletableDeferred<Unit>()
                    val task = async(Dispatchers.IO) {
                        session.execute(point,
                            capture = { vm.captureTempFitsForPlateSolve() }, fieldOfView = { .8 },
                            solve = { file, _ ->
                                outputs += file
                                assertFalse(camera.isCapturing.value)
                                assertTrue(vm.previewPaused.value)
                                enteredSolve.complete(Unit)
                                finishSolve.await()
                                assertFalse(camera.isCapturing.value)
                                PlateSolveResult(true, "Solved fixture", raDeg = 30.0 + point, decDeg = 20.0)
                            })
                    }
                    try {
                        await { camera.singleStarts == point + 1 }
                        delay(20); camera.emitLatest()
                        withTimeout(10_000) { enteredSolve.await() }
                        delay(80)
                        assertEquals(point + 1, camera.singleStarts)
                        assertFalse(camera.isCapturing.value)
                        finishSolve.complete(Unit)
                        assertTrue(task.await().success)
                        // The caller can now move the mount before explicitly requesting the next point.
                        assertFalse(camera.isCapturing.value)
                    } finally { finishSolve.complete(Unit); task.cancelAndJoin() }
                }
            } finally { outputs.forEach { it.delete() } }
        }
    }

    @Test fun cancellingAPolarExposureStopsItAndLeavesPreviewPaused() = runBlocking {
        workspace(tokens = true) { vm, camera ->
            val task = async(Dispatchers.IO) { vm.captureTempFitsForPlateSolve() }
            await { camera.singleStarts == 1 }
            task.cancelAndJoin()
            assertFalse(camera.isCapturing.value)
            assertTrue(vm.previewPaused.value)
            val stale = camera.emitLatest()
            assertEquals(1, camera.releases[stale]?.get())
            delay(100)
            assertEquals(1, camera.singleStarts)
            assertFalse(camera.isCapturing.value)
        }
    }

    @Test fun polarExposureCannotInterruptAnActiveRecordingEvenWithTheSameExposure() = runBlocking {
        workspace(tokens = true) { vm, camera ->
            await { camera.emitLatest(); vm.scienceCaptureReady.value }
            var output: File? = null
            try {
                main { vm.setRecordFormat("SER"); vm.startRecording() }
                assertTrue(vm.isRecording.value)
                output = vm.getActiveRecordingFile()
                val error = runCatching { vm.captureTempFitsForPlateSolve(camera.currentExposureUs) }.exceptionOrNull()
                assertNotNull(error)
                assertEquals(0, camera.singleStarts)
                assertTrue(camera.isCapturing.value)
                assertTrue(vm.isRecording.value)
                assertFalse(vm.previewPaused.value)
            } finally {
                main { vm.stopRecording() }
                await { vm.getActiveRecordingFile() == null }
                output?.let { it.delete(); File(it.parentFile, "${it.name}.capture.json").delete() }
            }
        }
    }

    @Test fun differentCameraCannotInheritOverrideAndRoiChangeRestoresAutoInsteadOfGuessingPhase() = runBlocking {
        workspace(tokens = true) { vm, camera ->
            main { assertTrue(vm.applyCameraCfa(SerCfaInterpretation.BGGR)) }
            await { camera.starts == 2 && !vm.cameraCfaApplying.value }
            val other = CameraFixture(true)
            try {
                camera.stopCapture()
                main {
                    vm.cameraManager.javaClass.getDeclaredField("activeCamera").apply { isAccessible=true }.set(vm.cameraManager, other)
                    vm.resumeCaptureForStacking()
                }
                await { other.starts == 1 }
                assertEquals(SerCfaInterpretation.AUTO, vm.cameraCfa.value)
                other.stopCapture()
                main {
                    vm.cameraManager.javaClass.getDeclaredField("activeCamera").apply { isAccessible=true }.set(vm.cameraManager, camera)
                    vm.resumeCaptureForStacking()
                }
                await { camera.starts == 3 }
                assertEquals(SerCfaInterpretation.BGGR, vm.cameraCfa.value)
                main { vm.setRoi(Roi(2,1,24,24)) }
                await { camera.starts == 4 && vm.cameraCfa.value == SerCfaInterpretation.AUTO }
                assertTrue(vm.statusMessage.value.contains(app.getString(com.indigo.mobileobservatory.R.string.camera_cfa_layout_changed)))
                assertEquals(0,camera.pixelFormatWrites)
            } finally { other.close() }
        }
    }

    private suspend fun workspace(tokens: Boolean, block: suspend (CameraViewModel, CameraFixture) -> Unit) {
        val store = ViewModelStore(); lateinit var vm: CameraViewModel
        val camera = CameraFixture(tokens)
        main { vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java] }
        try {
            await { vm.stacking.calibration.state.value.initialized }
            main {
                vm.cameraManager.javaClass.getDeclaredField("activeCamera").apply { isAccessible=true }.set(vm.cameraManager, camera)
                vm.resumeCaptureForStacking()
            }
            await { camera.starts >= 1 }
            block(vm, camera)
        } finally {
            main { store.clear() }
            val key = "camera_cfa_" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(CameraReferenceIdentity.of(camera).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            app.getSharedPreferences("mobile_observatory", 0).edit().remove(key).remove("${key}_layout").commit()
        }
    }
    private suspend fun await(test: () -> Boolean) = withTimeout(15_000) { while (!test()) delay(20) }

    private class CameraFixture(private val tokens: Boolean) : Camera, CameraSingleExposureCapable {
        override val isOpen = MutableStateFlow(true)
        override val isCapturing = MutableStateFlow(false)
        override val cameraInfo = CameraInfo("CFA${UUID.randomUUID().toString().take(8)}", UUID.randomUUID().toString(), 32,32,12)
        override val exposureRange = FloatRange(1f, 1_000_000f,10_000f)
        override val gainRange = FloatRange(0f,100f,10f)
        override val currentExposureUs = 10_000f
        override val currentGain = 10f
        @Volatile override var currentPixelFormat = PixelFormat.MONO12
        override val supportedPixelFormats = listOf(PixelFormat.MONO8, PixelFormat.MONO12)
        @Volatile override var currentRoi = Roi(1,1,24,24)
        override val cropInfo = CropInfo(0,0,24,24)
        override val hwExposureMaxUs = 1_000_000f
        override var longExposureEnabled = false
        override val sciencePixelTraits = PixelTraits(alignment=SampleAlignment.LEFT,validBits=12,bayerPatternAtOutput=false)
        @Volatile private var activeRun: Long? = null
        override val scienceCaptureGeneration get() = if (tokens) activeRun else null
        @Volatile var starts = 0
        @Volatile var singleStarts = 0
        @Volatile var lastFrameTimestamp = 0L
        @Volatile var pixelFormatWrites = 0
        private val callbacks = CopyOnWriteArrayList<FrameCallback>()
        private val ids = java.util.concurrent.atomic.AtomicLong()
        val releases = ConcurrentHashMap<ByteArray, AtomicInteger>()
        override fun startCapture(callback: FrameCallback) { callbacks.add(callback); starts++; activeRun=starts.toLong(); isCapturing.value=true }
        override fun startSingleExposure(callback: FrameCallback) { startCapture(callback); singleStarts++ }
        override fun stopCapture() { activeRun=null; isCapturing.value=false }
        fun emitLatest() = emit(callbacks.lastIndex, scienceCaptureGeneration)
        fun emit(index: Int, run: Long?, white: Boolean = false): ByteArray {
            val bytes = ByteArray(24*24*2)
            for (y in 0 until 24) for (x in 0 until 24) {
                val fraction = if (white) 1.0 else when { x%2==1 && y%2==1 -> .75; x%2==0 && y%2==0 -> .15; else -> .4 }
                val value = (fraction * 4095).toInt() shl 4
                val p = (y*24+x)*2; bytes[p]=value.toByte(); bytes[p+1]=(value ushr 8).toByte()
            }
            val timestamp = System.currentTimeMillis()
            lastFrameTimestamp = timestamp
            callbacks[index].onFrame(FrameData(bytes,24,24,currentPixelFormat,ids.incrementAndGet(),timestamp,run))
            return bytes
        }
        override fun recycleBuffer(buf: ByteArray) { releases.computeIfAbsent(buf) { AtomicInteger() }.incrementAndGet() }
        override fun close() { stopCapture(); isOpen.value=false }
        override fun setExposureTime(us: Float) {}
        override fun setGain(value: Float) {}
        override fun setPixelFormat(format: PixelFormat) { currentPixelFormat = format; pixelFormatWrites++ }
        override fun setRoi(roi: Roi) { currentRoi = roi }
        override fun resetRoi() {}
    }
}
