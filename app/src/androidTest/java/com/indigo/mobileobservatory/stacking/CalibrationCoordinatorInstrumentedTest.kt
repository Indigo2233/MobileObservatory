package com.indigo.mobileobservatory.stacking

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.recording.SERWriter
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Real SER, master library, coordinator, raw sensor calibration and exported FITS. */
@RunWith(AndroidJUnit4::class)
class CalibrationCoordinatorInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val key = StackSessionKey("calibration-fixture", 128, 96, PixelFormat.MONO16,
        Roi(0, 0, 128, 96), 1, 1, 100_000.0, 20f, "L", "raw16", 10f)

    @Test fun realMasterSelectionPersistsAndCalibratesOfflineBeforeAnalysisAndExport() = runBlocking {
        val folder = File(app.cacheDir, "calibration-pipeline-${System.nanoTime()}").apply { mkdirs() }
        val masters = File(folder, "masters")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val exports = mutableListOf<File>()
        var c = StackingCoordinator(app, scope, calibrationDirectory = masters)
        try {
            val bias = recording(folder, "bias.ser", 4) { 4096 }
            val lights = recording(folder, "lights.ser", 6) { pixel -> cleanPlanet(pixel) + 4096 }
            CalibrationRecordingContext.writeForRecording(bias, key)
            CalibrationRecordingContext.writeForRecording(lights, key)
            val originalHash = hash(lights)
            val biasHash = hash(bias)
            await { c.calibration.state.value.initialized && !c.calibration.state.value.busy }
            main { c.calibration.createMaster(CalibrationType.BIAS, bias) }
            await { !c.calibration.state.value.busy && c.calibration.state.value.masters.size == 1 }
            val descriptor = c.calibration.state.value.masters.single()
            assertEquals(4L, descriptor.frameCount)
            assertEquals("MONO", descriptor.geometry.layout)
            assertEquals(key.camera, descriptor.capture.camera)
            assertTrue(descriptor.sourceIdentity.startsWith("sha256:"))
            main { c.calibration.selectMaster(CalibrationType.BIAS, descriptor.id) }
            await { !c.calibration.state.value.busy && c.calibration.state.value.selectedIds[CalibrationType.BIAS] == descriptor.id }
            main { c.close() }
            c = StackingCoordinator(app, scope, calibrationDirectory = masters)
            await { c.calibration.state.value.initialized && !c.calibration.state.value.busy }
            assertEquals(descriptor.id, c.calibration.state.value.selectedIds[CalibrationType.BIAS])
            assertEquals(128L * 96 * 4, c.calibration.memoryBytes)
            main { c.calibration.deleteMaster(descriptor.id) }
            await { !c.calibration.state.value.busy }
            assertEquals("calibration_master_in_use", c.calibration.state.value.message)
            assertEquals(descriptor.id, c.calibration.state.value.masters.single().id)
            main { c.open(StackMode.PLANETARY); c.loadFile(lights) }
            awaitPhase(c, StackTaskPhase.READY)
            main { c.analyze(PixelRoi(4, 4, 120, 88), false) }
            awaitPhase(c, StackTaskPhase.ANALYZED)
            assertTrue(c.task.value.qualityFrames > 0)
            main { c.calibration.selectMaster(CalibrationType.BIAS, null) }
            assertEquals("calibration_reset_required", c.calibration.state.value.message)
            assertEquals(descriptor.id, c.calibration.state.value.selectedIds[CalibrationType.BIAS])
            main { c.stack(PlanetarySelection(100.0)) }
            awaitPhase(c, StackTaskPhase.COMPLETE)
            main { c.selectPreset(ProcessingPreset.RAW); c.export() }
            await { c.task.value.exportedFiles.size == 3 && !c.hasPlanetaryWork() }
            exports += c.task.value.exportedFiles.map(::File)
            val fits = exports.single { it.extension == "fits" }
            val output = StackFitsReader.read(fits)
            val metadata = StackFitsReader.readMetadata(fits)
            assertEquals("true", metadata["calibrated"])
            val provenance = JSONObject(metadata.getValue("calibration"))
            assertEquals("sensor_before_demosaic", provenance.getString("domain"))
            assertEquals(descriptor.id, provenance.getJSONObject("bias").getString("id"))
            assertEquals("[]", metadata["calibrationUnknownSettings"])
            assertEquals(.6, metadata.getValue("exposureSeconds").toDouble(), 1e-8)
            var error = 0.0; var samples = 0
            for (y in 8 until 88) for (x in 8 until 120) {
                val i = y * 128 + x
                error += abs(output.pixels[i] - cleanPlanet(i) / 65535f); samples++
            }
            assertTrue("The raw FITS must have the real bias removed, mean error=${error / samples}", error / samples < 0.0001)
            assertArrayEquals(originalHash, hash(lights))
            main { c.clearPlanetary() }
            awaitPhase(c, StackTaskPhase.EMPTY)
            main { c.calibration.selectMaster(CalibrationType.BIAS, null) }
            await { !c.calibration.state.value.busy && c.calibration.state.value.selectedIds.isEmpty() }
            main { c.calibration.deleteMaster(descriptor.id) }
            await { !c.calibration.state.value.busy && c.calibration.state.value.masters.isEmpty() }
            assertTrue("Deleting a master must preserve its original SER", bias.isFile)
            assertArrayEquals(biasHash, hash(bias))
        } finally {
            main { c.close() }; scope.cancel(); exports.forEach { it.delete() }
            folder.deleteRecursively()
            app.getSharedPreferences("stack-calibration-selection-${masters.name}", 0).edit().clear().commit()
        }
    }

    @Test fun liveBayerBiasIsRemovedBeforeDemosaicAndKnownSettingsCannotBeOverridden() = runBlocking {
        val folder = File(app.cacheDir, "calibration-live-${System.nanoTime()}").apply { mkdirs() }
        val masters = File(folder, "library-${System.nanoTime()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val c = StackingCoordinator(app, scope, calibrationDirectory = masters)
        val liveKey = key.copy(height = 128, format = PixelFormat.BAYER_RG16, roi = Roi(0, 0, 128, 128))
        try {
            val rawBias = FrameData(ByteArray(128 * 128 * 2), 128, 128, PixelFormat.BAYER_RG16, 0, 0)
            fun pedestal(x: Int, y: Int) = if (y % 2 == 0 && x % 2 == 0) 5000 else if (y % 2 == 1 && x % 2 == 1) 2000 else 3200
            for (y in 0 until 128) for (x in 0 until 128) put(rawBias.data, y * 128 + x, pedestal(x, y))
            val bias = File(folder, "bayer-bias.ser")
            SERWriter(bias).apply {
                open(128, 128, PixelFormat.BAYER_RG16)
                repeat(3) { writeFrame(rawBias.copy(frameId = it.toLong())) }
                close()
            }
            CalibrationRecordingContext.writeForRecording(bias, liveKey)
            await { c.calibration.state.value.initialized && !c.calibration.state.value.busy }
            main { c.calibration.createMaster(CalibrationType.BIAS, bias) }
            await { !c.calibration.state.value.busy && c.calibration.state.value.masters.size == 1 }
            val id = c.calibration.state.value.masters.single().id
            main { c.calibration.selectMaster(CalibrationType.BIAS, id, true) }
            await { !c.calibration.state.value.busy && c.calibration.state.value.selectedIds.isNotEmpty() }
            main { c.startLive(liveKey.copy(gain = 99f)) }
            assertEquals(LiveStackPhase.EMPTY, c.live.state.value.phase)
            assertTrue(c.task.value.message.startsWith("calibration_settings_mismatch"))
            val random = Random(17)
            val stars = List(20) { Triple(10.0 + random.nextDouble() * 108, 10.0 + random.nextDouble() * 108, 5000.0 + random.nextDouble() * 12000) }
            val clean = rawBias.copy(data = ByteArray(rawBias.data.size), frameId = 1)
            val light = rawBias.copy(data = ByteArray(rawBias.data.size), frameId = 1)
            for (y in 0 until 128) for (x in 0 until 128) {
                var value = 700.0
                stars.forEach { (sx, sy, amplitude) -> value += amplitude * exp(-((x - sx).pow(2) + (y - sy).pow(2)) / (2 * 1.4 * 1.4)) }
                val sample = value.roundToInt()
                put(clean.data, y * 128 + x, sample); put(light.data, y * 128 + x, sample + pedestal(x, y))
            }
            val expected = LinearPixelDecoder.decode(clean, PixelTraits(alignment = SampleAlignment.RIGHT))
            main { c.open(StackMode.LIVE); c.startLive(liveKey); c.acceptFrame(light, liveKey) }
            await { c.live.state.value.accepted == 1L || c.live.state.value.phase == LiveStackPhase.FAILED }
            assertEquals(1L, c.live.state.value.accepted)
            await { c.live.latestSnapshot != null }
            assertArrayEquals(expected.pixels, c.live.latestSnapshot!!.image.pixels, .00001f)
            main { c.calibration.selectMaster(CalibrationType.BIAS, null) }
            assertEquals("calibration_reset_required", c.calibration.state.value.message)
            main { c.resetLive(); c.calibration.selectMaster(CalibrationType.BIAS, null) }
            await { !c.calibration.state.value.busy && c.calibration.state.value.selectedIds.isEmpty() }
        } finally {
            main { c.close() }; scope.cancel(); folder.deleteRecursively()
            app.getSharedPreferences("stack-calibration-selection-${masters.name}", 0).edit().clear().commit()
        }
    }

    @Test fun cancelledCreationLeavesNoMasterAndMixedCaptureCannotBeConfirmedAway() = runBlocking {
        val folder = File(app.cacheDir, "calibration-cancel-${System.nanoTime()}").apply { mkdirs() }
        val masters = File(folder, "library-${System.nanoTime()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val busySource = AtomicBoolean(false)
        val c = StackingCoordinator(app, scope, calibrationDirectory = masters, isSourceBusy = { busySource.get() })
        try {
            val source = recording(folder, "unfinished-master-input.ser", 800) { 1024 }
            val original = hash(source)
            await { c.calibration.state.value.initialized }
            main { c.calibration.createMaster(CalibrationType.BIAS, source, true); c.calibration.cancel() }
            await { !c.calibration.state.value.busy }
            assertTrue(c.calibration.state.value.masters.isEmpty())
            assertTrue(masters.listFiles().orEmpty().none { it.extension == "icm" || it.name.endsWith(".partial") })
            assertArrayEquals(original, hash(source))
            busySource.set(true)
            main { c.calibration.createMaster(CalibrationType.BIAS, source, true) }
            await { !c.calibration.state.value.busy }
            assertEquals("recording_active", c.calibration.state.value.message)
            busySource.set(false)
            CalibrationRecordingContext.writeForRecording(source, key)
            CalibrationRecordingContext.invalidate(source, "gain changed during recording")
            main { c.calibration.createMaster(CalibrationType.BIAS, source, true) }
            await { !c.calibration.state.value.busy }
            assertEquals("calibration_capture_changed", c.calibration.state.value.message)
            assertTrue(c.calibration.state.value.masters.isEmpty())
        } finally {
            main { c.close() }; scope.cancel(); folder.deleteRecursively()
            app.getSharedPreferences("stack-calibration-selection-${masters.name}", 0).edit().clear().commit()
        }
    }

    @Test fun multipleMissingPersistedMastersCanBeDisabledWithoutLoadingEither() = runBlocking {
        val directory = File(app.cacheDir, "missing-masters-${System.nanoTime()}")
        val preferences = app.getSharedPreferences("stack-calibration-selection-${directory.name}", 0)
        preferences.edit().putString("BIAS", "missing-bias").putString("DARK", "missing-dark").commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val c = StackingCoordinator(app, scope, calibrationDirectory = directory)
        try {
            await { c.calibration.state.value.initialized && !c.calibration.state.value.busy }
            assertEquals(2, c.calibration.state.value.selectedIds.size)
            assertEquals("calibration_master_missing", c.calibration.state.value.message)
            main { c.startLive(key) }
            assertEquals(LiveStackPhase.EMPTY, c.live.state.value.phase)
            assertEquals("calibration_selection_unavailable", c.task.value.message)
            main { c.calibration.clearSelection() }
            await { !c.calibration.state.value.busy && c.calibration.state.value.selectedIds.isEmpty() }
            assertFalse(preferences.contains("BIAS")); assertFalse(preferences.contains("DARK"))
            main { c.startLive(key) }
            assertEquals(LiveStackPhase.RUNNING, c.live.state.value.phase)
        } finally { main { c.close() }; scope.cancel(); directory.deleteRecursively(); preferences.edit().clear().commit() }
    }

    @Test fun cancellingRestoreBeforeItsWorkerStartsStillUnlocksExplicitRecovery() = runBlocking {
        val directory = File(app.cacheDir, "cancel-restore-${System.nanoTime()}")
        val preferences = app.getSharedPreferences("stack-calibration-selection-${directory.name}", 0)
        preferences.edit().putString("BIAS", "unread-bias").putString("DARK", "unread-dark").commit()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val occupied = CountDownLatch(1); val release = CountDownLatch(1)
        scope.launch { occupied.countDown(); release.await(10, TimeUnit.SECONDS) }
        assertTrue(occupied.await(5, TimeUnit.SECONDS))
        val calibration = CalibrationCoordinator(app, scope, Any(), 64L * 1024 * 1024, { null }, directory = directory)
        try {
            assertTrue(calibration.state.value.busy)
            calibration.cancel()
            release.countDown()
            await { calibration.state.value.initialized && !calibration.state.value.busy }
            assertEquals("calibration_cancelled", calibration.state.value.message)
            assertEquals(2, calibration.state.value.selectedIds.size)
            calibration.clearSelection()
            await { !calibration.state.value.busy && calibration.state.value.selectedIds.isEmpty() }
            assertFalse(calibration.snapshot().enabled)
        } finally {
            release.countDown(); calibration.close(); scope.cancel(); dispatcher.close()
            directory.deleteRecursively(); preferences.edit().clear().commit()
        }
    }

    private fun recording(folder: File, name: String, count: Int, sample: (Int) -> Int): File {
        val file = File(folder, name)
        val bytes = ByteArray(128 * 96 * 2)
        for (pixel in 0 until 128 * 96) put(bytes, pixel, sample(pixel))
        SERWriter(file).apply {
            open(128, 96, PixelFormat.MONO16)
            repeat(count) { writeFrame(FrameData(bytes, 128, 96, PixelFormat.MONO16, it.toLong(), it * 100_000L)) }
            close()
        }
        return file
    }

    private fun cleanPlanet(index: Int): Int {
        val x = index % 128; val y = index / 128
        val r = hypot((x - 64.0) / 44, (y - 48.0) / 32)
        return if (r < 1) (18000 + 6000 * sin(x * .27) * cos(y * .31) + 3000 * cos((x + y) * .51)).roundToInt() else 500
    }
    private fun put(bytes: ByteArray, index: Int, value: Int) { bytes[index * 2] = value.toByte(); bytes[index * 2 + 1] = (value ushr 8).toByte() }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(30_000) { while (!condition()) delay(20) }
    private suspend fun awaitPhase(c: StackingCoordinator, phase: StackTaskPhase) {
        await { !c.hasPlanetaryWork() && (c.task.value.phase == phase || c.task.value.phase == StackTaskPhase.FAILED) }
        assertEquals(c.task.value.message, phase, c.task.value.phase)
    }
}
