package com.indigo.mobileobservatory.stacking

import android.app.Application
import android.graphics.BitmapFactory
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.CameraScienceLayouts
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production coordinator, linear RAW16/RGB48 decode, registration, display and full-size export.
 * Heap samples describe this Android process; they do not include a physical camera SDK. */
@RunWith(AndroidJUnit4::class)
class Live585MemoryInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test fun fullSensorColourSoftwareBin2CanStackExportAndResume() = runBlocking { live(2) }

    @Test fun fullResolutionColourCanStackExportAndResume() = runBlocking { live(1) }

    @Test fun fullSensorRgb48SoftwareBin2CanStackExportAndResume() = runBlocking { live(2, PixelFormat.RGB48) }

    @Test fun fullResolutionOfflineColourCanStackAndExportWithBothFilters() = runBlocking {
        workspace("offline-full") { coordinator, directory, exports, measurement ->
            val source = OfflineRegionInstrumentedTest.recording(directory, stars = true, frames = 2)
            main {
                coordinator.open(StackMode.PLANETARY)
                assertTrue(coordinator.setTarget(StackMode.PLANETARY, StackTarget.DEEP_SKY))
                coordinator.loadFile(source)
            }
            phase(coordinator, StackTaskPhase.READY)
            val plan = coordinator.offlinePlan()!!
            assertNull(plan.issue(coordinator.memoryBudgetBytes))
            main { coordinator.stackDeepSky() }
            phase(coordinator, StackTaskPhase.COMPLETE)
            assertEquals(2, coordinator.task.value.deepSkyStats!!.stack.accepted)
            main { coordinator.updateProcessing(filtered()) }
            settled(coordinator)
            main { coordinator.export() }
            phase(coordinator, StackTaskPhase.COMPLETE)
            exports += coordinator.task.value.exportedFiles.map(::File)
            verifyExport(exports, 3856, 2180)
            measurement.put("estimatedWorkingBytes", plan.workingBytes)
                .put("acceptedFrames", coordinator.task.value.deepSkyStats!!.stack.accepted)
        }
    }

    private suspend fun live(bin: Int, format: PixelFormat = PixelFormat.BAYER_RG16) =
        workspace(if (format == PixelFormat.RGB48) "live-rgb48-bin$bin" else "live-bin$bin") { coordinator, directory, exports, measurement ->
        val bytes = if (format == PixelFormat.RGB48) {
            measurement.put("input", "3856x2180 RGB48")
            rgb48Stars()
        } else {
            val source = OfflineRegionInstrumentedTest.recording(directory, stars = true)
            SERReader(source).use { reader ->
                assertTrue(reader.open())
                requireNotNull(reader.readFrame(0))
            }
        }
        // Synthetic RGB48 is explicitly linear/right-aligned; SDK raw-CFA mappings exclude processed RGB.
        val traits = if (format == PixelFormat.RGB48) PixelTraits(validBits = 16, alignment = SampleAlignment.RIGHT)
            else requireNotNull(CameraScienceLayouts.fullContainer(format))
        val key = StackSessionKey("IMX585 synthetic", 3856, 2180, format,
            Roi(0, 0, 3856, 2180), 1, bin, 5_000_000.0, 100f, "none", "raw",
            pixelTraits = traits)
        main {
            coordinator.open(StackMode.LIVE)
            coordinator.selectPreset(ProcessingPreset.RAW)
            coordinator.startLive(key)
        }
        assertEquals(coordinator.task.value.message, LiveStackPhase.RUNNING, coordinator.live.state.value.phase)
        suspend fun add(id: Long) {
            main { coordinator.acceptFrame(FrameData(bytes, 3856, 2180, key.format, id, id * 5000), key) }
            await { coordinator.live.state.value.let { it.accepted + it.rejected >= id || it.phase == LiveStackPhase.FAILED } }
            assertEquals(coordinator.live.state.value.toString(), id, coordinator.live.state.value.accepted)
        }
        add(1)
        await { coordinator.livePreview.value != null && !coordinator.rendering.value }
        add(2)
        main { coordinator.live.pause() }
        await { coordinator.live.state.value.phase == LiveStackPhase.PAUSED &&
            coordinator.live.latestSnapshot?.state?.accepted == 2L && !coordinator.rendering.value }
        val preview = coordinator.live.latestSnapshot!!
        val original = preview.image.pixels.copyOf()
        assertTrue(preview.image.width <= LiveStackMemory.PREVIEW_MAX_SIDE)
        assertTrue(preview.image.height <= LiveStackMemory.PREVIEW_MAX_SIDE)
        main { coordinator.updateProcessing(filtered()) }
        settled(coordinator)
        main { coordinator.export() }
        phase(coordinator, StackTaskPhase.COMPLETE)
        exports += coordinator.task.value.exportedFiles.map(::File)
        val width = 3856 / bin
        val height = 2180 / bin
        verifyExport(exports, width, height)
        val fits = exports.single { it.extension == "fits" }
        val metadata = StackFitsReader.readMetadata(fits)
        assertEquals("2", metadata["frameCount"])
        assertEquals(bin.toString(), metadata["softwareBin"])
        assertEquals(10.0, metadata.getValue("exposureSeconds").toDouble(), 0.0)
        assertArrayEquals(original, preview.image.pixels, 0f)
        RandomAccessFile(fits, "r").use { file ->
            FitsHeaderParser.read(file)
            val start = file.filePointer
            val grid = StackPreviewGrid(width, height, LiveStackMemory.PREVIEW_MAX_SIDE)
            for (p in preview.weights.indices step 199) for (channel in 0..2) {
                file.seek(start + (channel.toLong() * width * height + grid.sourcePixel(p)) * 4)
                assertEquals(preview.image.pixels[p * 3 + channel], file.readFloat(), 1e-6f)
            }
        }
        main { assertTrue(coordinator.live.resume(key)) }
        add(3)
        main { coordinator.live.pause() }
        await { coordinator.live.state.value.phase == LiveStackPhase.PAUSED }
        assertArrayEquals(original, preview.image.pixels, 0f)
        measurement.put("estimatedWorkingBytes", LiveStackAdmission.workingBytes(key, 0, false))
            .put("acceptedFrames", coordinator.live.state.value.accepted)
            .put("outputWidth", width).put("outputHeight", height)
            .put("previewWidth", preview.image.width).put("previewHeight", preview.image.height)
    }

    private fun rgb48Stars(): ByteArray {
        val signals = IntArray(400 * 300) { p ->
            val dx = (p % 400 + 200) % 400 - 200
            val dy = (p / 400 + 150) % 300 - 150
            ((.015 + .5 * kotlin.math.exp(-(dx * dx + dy * dy) / 98.0)) * 65535).toInt()
        }
        val bytes = ByteArray(3856 * 2180 * 6)
        for (y in 0 until 2180) for (x in 0 until 3856) for (c in 0..2) {
            val code = (signals[y % 300 * 400 + x % 400] * when(c) { 0 -> 1.2; 1 -> 1.0; else -> .8 }).toInt()
            val i = (y * 3856 + x) * 6 + c * 2
            bytes[i] = code.toByte(); bytes[i + 1] = (code ushr 8).toByte()
        }
        return bytes
    }

    private fun filtered() = StackProcessingParameters.preset(ProcessingPreset.INTENSE)
        .copy(sharpening = .3, localContrast = .25)

    private fun verifyExport(files: List<File>, width: Int, height: Int) {
        assertEquals(3, files.size)
        val fits = files.single { it.extension == "fits" }
        RandomAccessFile(fits, "r").use { reader ->
            val header = FitsHeaderParser.read(reader)
            assertEquals(width.toString(), header["NAXIS1"])
            assertEquals(height.toString(), header["NAXIS2"])
            assertEquals("3", header["NAXIS3"])
            val start = reader.filePointer
            val rgb = FloatArray(3) { channel ->
                reader.seek(start + (channel.toLong() * width * height + height / 2L * width + width / 2) * 4)
                reader.readFloat()
            }
            assertTrue(rgb.all { it.isFinite() && it > 0 })
            assertTrue(rgb[0] > rgb[1] && rgb[1] > rgb[2])
        }
        val png = files.single { it.extension == "png" }
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.absolutePath, size)
        assertEquals(width, size.outWidth)
        assertEquals(height, size.outHeight)
        val metadata = JSONObject(files.single { it.extension == "json" }.readText())
        assertEquals(width.toString(), metadata.getString("width"))
        assertEquals(height.toString(), metadata.getString("height"))
        assertEquals("16", metadata.getString("processedPngBits"))
    }

    private suspend fun workspace(name: String,
        block: suspend (StackingCoordinator, File, MutableList<File>, JSONObject) -> Unit) {
        val directory = File(app.cacheDir, "585-$name-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val coordinator = StackingCoordinator(app, scope, calibrationDirectory = File(directory, "masters"))
        val exports = mutableListOf<File>()
        val runtime = Runtime.getRuntime()
        val javaPeak = AtomicLong()
        val nativePeak = AtomicLong()
        val sampler = scope.launch(Dispatchers.Default) {
            while (isActive) {
                javaPeak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                delay(10)
            }
        }
        val measurement = JSONObject().put("case", name).put("input", "3856x2180 RAW16 RGGB DDFAPD")
            .put("heapLimitBytes", runtime.maxMemory()).put("budgetCeilingBytes", coordinator.memoryBudgetBytes)
            .put("measurement", "Android Java/native heap sampled every 10ms; synthetic camera bytes")
        val started = System.nanoTime()
        try {
            await { coordinator.calibration.state.value.initialized }
            block(coordinator, directory, exports, measurement)
            measurement.put("passed", true)
        } finally {
            sampler.cancelAndJoin()
            measurement.put("sampledJavaHeapPeakBytes", javaPeak.get())
                .put("sampledNativeHeapPeakBytes", nativePeak.get())
                .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
            File(app.getExternalFilesDir(null), "585-$name-measurement.json").writeText(measurement.toString(2))
            main { coordinator.close() }
            scope.cancel()
            exports.forEach { it.delete() }
            directory.deleteRecursively()
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private suspend fun await(condition: () -> Boolean) = withTimeout(180_000) { while (!condition()) delay(25) }
    private suspend fun settled(c: StackingCoordinator) = await { !c.hasPlanetaryWork() && !c.rendering.value }
    private suspend fun phase(c: StackingCoordinator, expected: StackTaskPhase) {
        await { !c.hasPlanetaryWork() && !c.rendering.value &&
            (c.task.value.phase == expected || c.task.value.phase == StackTaskPhase.FAILED) }
        assertEquals(c.task.value.message, expected, c.task.value.phase)
    }
}
