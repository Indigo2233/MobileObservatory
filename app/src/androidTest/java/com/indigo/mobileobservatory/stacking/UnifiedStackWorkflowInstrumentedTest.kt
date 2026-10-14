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
import java.util.Random
import kotlin.math.*

/** Real file decoding, star registration, target invalidation and export provenance. */
@RunWith(AndroidJUnit4::class)
class UnifiedStackWorkflowInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val width = 192
    private val height = 160
    private val key = StackSessionKey("unified-fixture", width, height, PixelFormat.MONO16,
        Roi(0, 0, width, height), 1, 1, 250_000.0, 10f, "L", "raw", offset = 10f)

    @Test fun offlineDeepSkyUsesStarRegistrationAndExportsExplicitUnknownExposure() = runBlocking {
        workspace { coordinator, folder, exports ->
            val file = recording(folder, 3)
            main { assertTrue(coordinator.setTarget(StackMode.PLANETARY, StackTarget.DEEP_SKY)); coordinator.open(StackMode.PLANETARY, file) }
            phase(coordinator, StackTaskPhase.READY)
            main { coordinator.stackDeepSky() }
            phase(coordinator, StackTaskPhase.COMPLETE)
            val stats = coordinator.task.value.deepSkyStats!!
            assertNull(coordinator.task.value.stats)
            assertEquals(3, stats.stack.accepted)
            assertEquals(3L, stats.unknownExposureFrames)
            assertEquals(0.0, stats.stack.exposureSeconds, 0.0)
            assertTrue(stats.stack.stars >= 6)
            assertTrue(coordinator.hasResult(StackMode.PLANETARY))
            settle(coordinator)
            main { coordinator.export() }
            await { coordinator.task.value.exportedFiles.size == 3 && !coordinator.hasPlanetaryWork() }
            exports += coordinator.task.value.exportedFiles.map(::File)
            val metadata = JSONObject(exports.single { it.extension == "json" }.readText())
            assertEquals("offline_deep_sky", metadata.getString("kind"))
            assertEquals("offline", metadata.getString("workflow"))
            assertEquals("deep_sky", metadata.getString("target"))
            assertEquals("3", metadata.getString("unknownExposureFrames"))
            assertEquals("rigid_star_ransac_online_welford_sigma", metadata.getString("algorithm"))
            assertEquals("false", metadata.getString("calibrated"))
            val calibration = JSONObject(metadata.getString("calibration"))
            assertTrue(calibration.isNull("bias") && calibration.isNull("dark") && calibration.isNull("flat"))
            val image = StackFitsReader.read(exports.single { it.extension == "fits" })
            assertEquals(1, image.channels)
            assertTrue(image.pixels.all { it.isFinite() })
        }
    }

    @Test fun liveTargetRequiresConfirmationAndRejectsAStaleCameraSessionKey() = runBlocking {
        workspace { coordinator, _, _ ->
            main { coordinator.open(StackMode.LIVE); coordinator.startLive(key); coordinator.acceptFrame(frame(1), key) }
            await { coordinator.live.state.value.accepted == 1L }
            val oldGeneration = coordinator.live.state.value.generation
            main {
                assertFalse(coordinator.setTarget(StackMode.LIVE, StackTarget.PLANETARY))
                assertEquals(StackTarget.DEEP_SKY, coordinator.liveTarget.value)
                assertTrue(coordinator.setTarget(StackMode.LIVE, StackTarget.PLANETARY, resetConfirmed = true))
            }
            assertEquals(StackTarget.PLANETARY, coordinator.liveTarget.value)
            assertEquals(StackTarget.PLANETARY, coordinator.offlineTarget.value)
            assertEquals(LiveStackPhase.EMPTY, coordinator.live.state.value.phase)
            assertTrue(coordinator.live.state.value.generation > oldGeneration)
            assertFalse(coordinator.hasResult(StackMode.LIVE))
            assertNull(coordinator.livePreview.value)
            main { coordinator.startLive(key) }
            assertEquals(LiveStackPhase.EMPTY, coordinator.live.state.value.phase)
            assertEquals("stack_target_changed", coordinator.task.value.message)
            main { coordinator.startLive(key.copy(target = StackTarget.PLANETARY)); coordinator.open(StackMode.PLANETARY); coordinator.open(StackMode.LIVE) }
            assertEquals(StackTarget.PLANETARY, coordinator.target(StackMode.LIVE))
            assertEquals(StackTarget.PLANETARY, coordinator.live.state.value.key!!.target)
        }
    }

    @Test fun livePlanetaryDefaultStartupAndExportCountOnlyActualContributions() = runBlocking {
        workspace { coordinator, _, exports ->
            val planetaryKey = key.copy(target = StackTarget.PLANETARY)
            val bytes = ByteArray(width * height * 2)
            for (y in 0 until height) for (x in 0 until width) {
                val radius = hypot((x - width / 2.0) / 1.1, y - height / 2.0)
                val limb = .5 * (1 - tanh((radius - 44) / .8))
                val texture = .045 * sin(y * .47) + .025 * sin(y * .23 + x * .14) + .025 * sin(x * .61 + y * .24) * cos(y * .49)
                val value = ((.012 + limb * (.37 + texture + .08 * max(0.0, 1 - radius / 44))) * 65535).roundToInt()
                bytes[(y * width + x) * 2] = value.toByte()
                bytes[(y * width + x) * 2 + 1] = (value ushr 8).toByte()
            }
            main { assertTrue(coordinator.setTarget(StackMode.LIVE, StackTarget.PLANETARY)); coordinator.open(StackMode.LIVE); coordinator.startLive(planetaryKey) }
            repeat(2) { index ->
                main { coordinator.acceptFrame(FrameData(bytes, width, height, PixelFormat.MONO16, index + 1L, index * 250_000L), planetaryKey) }
                await { coordinator.live.state.value.rejected == index + 1L }
                assertEquals("REFERENCE_WARMUP", coordinator.live.state.value.reason)
                assertNull(coordinator.live.latestSnapshot)
            }
            main { coordinator.acceptFrame(FrameData(bytes, width, height, PixelFormat.MONO16, 3L, 500_000L), planetaryKey) }
            await { coordinator.live.state.value.accepted == 1L && coordinator.livePreview.value != null }
            main { coordinator.live.pause() }
            await { coordinator.live.state.value.phase == LiveStackPhase.PAUSED && !coordinator.rendering.value }
            assertEquals(.25, coordinator.live.state.value.exposureSeconds, 0.0)
            assertEquals(1L, coordinator.live.state.value.imageVersion)
            main { coordinator.export() }
            await { coordinator.task.value.exportedFiles.size == 3 && !coordinator.hasPlanetaryWork() }
            exports += coordinator.task.value.exportedFiles.map(::File)
            val metadata = StackFitsReader.readMetadata(exports.single { it.extension == "fits" })
            assertEquals("live_planetary", metadata["kind"])
            assertEquals("planetary", metadata["target"])
            assertEquals("1", metadata["frameCount"])
            assertEquals("3", metadata["referenceWarmupFrames"])
            assertEquals("2", metadata["referenceWarmupDiscardedFrames"])
            assertEquals("not_measured", metadata["registrationRmsPx"])
            assertEquals(.25, metadata.getValue("exposureSeconds").toDouble(), 0.0)
        }
    }

    @Test fun offlineDeepSkyAppliesOptionalBiasAndKeepsValidatedCaptureExposure() = runBlocking {
        workspace { coordinator, folder, exports ->
            val clean = frame(0)
            val biasValue = 1311
            val biasData = ByteArray(width * height * 2).also { bytes ->
                for (p in 0 until width * height) { bytes[p * 2] = biasValue.toByte(); bytes[p * 2 + 1] = (biasValue ushr 8).toByte() }
            }
            val lightData = clean.data.copyOf().also { bytes ->
                for (p in 0 until width * height) {
                    val value = (bytes[p * 2].toInt() and 255) + ((bytes[p * 2 + 1].toInt() and 255) shl 8) + biasValue
                    bytes[p * 2] = value.toByte(); bytes[p * 2 + 1] = (value ushr 8).toByte()
                }
            }
            val bias = File(folder, "bias.ser")
            val lights = File(folder, "calibrated-lights.ser")
            for ((file, bytes) in listOf(bias to biasData, lights to lightData)) {
                SERWriter(file).apply {
                    open(width, height, PixelFormat.MONO16)
                    try { repeat(3) { writeFrame(FrameData(bytes, width, height, PixelFormat.MONO16, it.toLong(), it * 250_000L)) } }
                    finally { close() }
                }
                CalibrationRecordingContext.writeForRecording(file, key)
            }
            main { coordinator.calibration.createMaster(CalibrationType.BIAS, bias) }
            await { !coordinator.calibration.state.value.busy && coordinator.calibration.state.value.masters.size == 1 }
            val master = coordinator.calibration.state.value.masters.single()
            main { coordinator.calibration.selectMaster(CalibrationType.BIAS, master.id) }
            await { !coordinator.calibration.state.value.busy && coordinator.calibration.state.value.selectedIds[CalibrationType.BIAS] == master.id }
            main { assertTrue(coordinator.setTarget(StackMode.PLANETARY, StackTarget.DEEP_SKY)); coordinator.open(StackMode.PLANETARY, lights) }
            phase(coordinator, StackTaskPhase.READY)
            main { coordinator.stackDeepSky() }
            phase(coordinator, StackTaskPhase.COMPLETE)
            assertEquals(.75, coordinator.task.value.deepSkyStats!!.stack.exposureSeconds, 0.0)
            assertEquals(0L, coordinator.task.value.deepSkyStats!!.unknownExposureFrames)
            settle(coordinator)
            main { coordinator.export() }
            await { coordinator.task.value.exportedFiles.size == 3 && !coordinator.hasPlanetaryWork() }
            exports += coordinator.task.value.exportedFiles.map(::File)
            val output = StackFitsReader.read(exports.single { it.extension == "fits" })
            val metadata = StackFitsReader.readMetadata(exports.single { it.extension == "fits" })
            assertEquals("true", metadata["calibrated"])
            assertEquals(master.id, JSONObject(metadata.getValue("calibration")).getJSONObject("bias").getString("id"))
            assertEquals("[]", metadata["calibrationUnknownSettings"])
            val expected = FloatArray(width * height) { p ->
                ((clean.data[p * 2].toInt() and 255) + ((clean.data[p * 2 + 1].toInt() and 255) shl 8)) / 65535f
            }
            assertArrayEquals(expected, output.pixels, 1e-5f)
        }
    }

    @Test fun confirmedOfflineSwitchCancelsRunningAnalysisAndRetainsOnlyTheSource() = runBlocking {
        workspace { coordinator, folder, _ ->
            val file = recording(folder, 120)
            main { coordinator.open(StackMode.PLANETARY, file) }
            phase(coordinator, StackTaskPhase.READY)
            main { coordinator.analyze(null, false) }
            await { coordinator.task.value.phase == StackTaskPhase.ANALYZING && coordinator.task.value.completed > 0 }
            main {
                assertFalse(coordinator.setTarget(StackMode.PLANETARY, StackTarget.DEEP_SKY))
                assertTrue(coordinator.setTarget(StackMode.PLANETARY, StackTarget.DEEP_SKY, resetConfirmed = true))
            }
            phase(coordinator, StackTaskPhase.READY)
            assertEquals(StackTarget.DEEP_SKY, coordinator.offlineTarget.value)
            assertEquals(file.canonicalPath, coordinator.task.value.source!!.file.canonicalPath)
            assertEquals(0L, coordinator.task.value.qualityFrames)
            assertTrue(coordinator.task.value.histogram.isEmpty())
            assertNull(coordinator.task.value.stats)
            assertNull(coordinator.task.value.deepSkyStats)
            assertFalse(coordinator.hasResult(StackMode.PLANETARY))
            main { coordinator.stackDeepSky() }
            phase(coordinator, StackTaskPhase.COMPLETE)
            assertEquals(120, coordinator.task.value.deepSkyStats!!.stack.accepted)
            main { assertTrue(coordinator.setTarget(StackMode.PLANETARY, StackTarget.PLANETARY, resetConfirmed = true)) }
            phase(coordinator, StackTaskPhase.READY)
            assertFalse(coordinator.hasResult(StackMode.PLANETARY))
            assertNull(coordinator.task.value.deepSkyStats)
        }
    }

    @Test fun savingALiveResultOwnsAutoAdaptationAcrossNavigationAndRejectsAnOfflineTargetSwitch() = runBlocking {
        workspace { coordinator, _, exports ->
            main { coordinator.open(StackMode.LIVE); coordinator.startLive(key); coordinator.acceptFrame(frame(1), key) }
            await { coordinator.live.state.value.accepted == 1L && coordinator.livePreview.value != null && !coordinator.rendering.value }
            main { coordinator.live.pause() }
            await { coordinator.live.state.value.phase == LiveStackPhase.PAUSED }
            val offlineRecipe = StackProcessingParameters.preset(ProcessingPreset.INTENSE).copy(saturation = 1.37, whitePoint = .63)
            val lock = StackingCoordinator::class.java.getDeclaredField("stateLock").apply { isAccessible = true }.get(coordinator)
            main {
                // Hold the coordinator's transition monitor across adjacent UI actions so the
                // real worker cannot finish a small export before the ownership assertion.
                synchronized(lock) {
                    coordinator.autoStretch()
                    coordinator.export()
                    assertEquals(StackTaskPhase.EXPORTING, coordinator.task.value.phase)
                    coordinator.open(StackMode.PLANETARY)
                    assertEquals("stack_export_busy", coordinator.targetChangeBlockedReason(StackMode.PLANETARY))
                    assertFalse(coordinator.setTarget(StackMode.PLANETARY, StackTarget.DEEP_SKY, resetConfirmed = true))
                    assertEquals("stack_export_busy", coordinator.task.value.message)
                    assertEquals(StackTarget.PLANETARY, coordinator.offlineTarget.value)
                    coordinator.updateProcessing(offlineRecipe)
                }
            }
            await { coordinator.task.value.exportedFiles.size == 3 && !coordinator.hasPlanetaryWork() }
            exports += coordinator.task.value.exportedFiles.map(::File)
            assertEquals("Saving the captured live recipe cannot overwrite an offline edit", offlineRecipe, coordinator.processing.value)
            val metadata = JSONObject(exports.single { it.extension == "json" }.readText())
            assertEquals("live", metadata.getString("workflow"))
            assertEquals("deep_sky", metadata.getString("target"))
            val savedRecipe = StackResultMetadata.parseParameters(JSONObject(metadata.getString("processing")).getJSONObject("parameters"))
            main { coordinator.open(StackMode.LIVE) }
            assertEquals("The original live workspace adopts its saved automatic recipe", savedRecipe, coordinator.processing.value)
            assertNull(coordinator.targetChangeBlockedReason(StackMode.PLANETARY))
        }
    }

    @Test fun switchingTheOfflineTargetDuringLiveAcquisitionDiscardsItsOldAnalysisWithoutTouchingLivePixels() = runBlocking {
        workspace { coordinator, folder, _ ->
            val file = recording(folder, 3)
            main { coordinator.open(StackMode.PLANETARY, file) }
            phase(coordinator, StackTaskPhase.READY)
            main { coordinator.analyze(null, false) }
            phase(coordinator, StackTaskPhase.ANALYZED)
            assertTrue(coordinator.task.value.histogram.isNotEmpty())
            val analysisField = StackingCoordinator::class.java.getDeclaredField("analysis").apply { isAccessible = true }
            val oldAnalysis = analysisField.get(coordinator) as PlanetaryAnalysis
            assertTrue(oldAnalysis.indexFile.isFile)
            main { coordinator.open(StackMode.LIVE); coordinator.startLive(key); coordinator.acceptFrame(frame(1), key) }
            await { coordinator.live.state.value.accepted == 1L && coordinator.live.latestSnapshot != null }
            val liveSnapshot = coordinator.live.latestSnapshot!!
            val generation = coordinator.live.state.value.generation
            main { assertTrue(coordinator.setTarget(StackMode.PLANETARY, StackTarget.DEEP_SKY, resetConfirmed = true)) }
            assertEquals(StackTarget.DEEP_SKY, coordinator.offlineTarget.value)
            assertEquals(StackTaskPhase.READY, coordinator.task.value.phase)
            assertEquals(file.canonicalPath, coordinator.task.value.source!!.file.canonicalPath)
            assertTrue(coordinator.task.value.histogram.isEmpty())
            assertEquals(0L, coordinator.task.value.qualityFrames)
            assertNull(analysisField.get(coordinator))
            assertFalse(oldAnalysis.indexFile.exists())
            assertEquals(LiveStackPhase.RUNNING, coordinator.live.state.value.phase)
            assertEquals(generation, coordinator.live.state.value.generation)
            assertEquals(1L, coordinator.live.state.value.accepted)
            assertSame(liveSnapshot, coordinator.live.latestSnapshot)
            main { coordinator.resetLive(); coordinator.open(StackMode.PLANETARY); coordinator.stackDeepSky() }
            phase(coordinator, StackTaskPhase.COMPLETE)
            assertEquals(3, coordinator.task.value.deepSkyStats!!.stack.accepted)
        }
    }

    @Test fun anEditAfterSaveIsPreservedWhileTheOutputUsesItsCapturedRecipe() = runBlocking {
        workspace { coordinator, _, exports ->
            main { coordinator.open(StackMode.LIVE); coordinator.startLive(key); coordinator.acceptFrame(frame(1), key) }
            await { coordinator.live.state.value.accepted == 1L && coordinator.livePreview.value != null && !coordinator.rendering.value }
            main { coordinator.live.pause() }
            await { coordinator.live.state.value.phase == LiveStackPhase.PAUSED }
            val clicked = coordinator.processing.value.copy(saturation = 1.2, whitePoint = .9)
            val later = clicked.copy(saturation = 1.83, whitePoint = .77)
            val lock = StackingCoordinator::class.java.getDeclaredField("stateLock").apply { isAccessible = true }.get(coordinator)
            main {
                synchronized(lock) {
                    coordinator.updateProcessing(clicked)
                    coordinator.autoStretch()
                    coordinator.export()
                    coordinator.updateProcessing(later)
                }
            }
            await { coordinator.task.value.exportedFiles.size == 3 && !coordinator.hasPlanetaryWork() }
            exports += coordinator.task.value.exportedFiles.map(::File)
            assertEquals(later, coordinator.processing.value)
            val metadata = JSONObject(exports.single { it.extension == "json" }.readText())
            val savedRecipe = StackResultMetadata.parseParameters(JSONObject(metadata.getString("processing")).getJSONObject("parameters"))
            assertEquals(clicked.saturation, savedRecipe.saturation, 0.0)
            assertNotEquals(later, savedRecipe)
        }
    }

    private suspend fun workspace(block: suspend (StackingCoordinator, File, MutableList<File>) -> Unit) {
        val folder = File(app.cacheDir, "unified-stack-${System.nanoTime()}").apply { mkdirs() }
        val library = File(folder, "masters-${System.nanoTime()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val coordinator = StackingCoordinator(app, scope, calibrationDirectory = library)
        val exports = mutableListOf<File>()
        try { await { coordinator.calibration.state.value.initialized }; block(coordinator, folder, exports) }
        finally {
            main { coordinator.close() }; scope.cancel(); exports.forEach { it.delete() }; folder.deleteRecursively()
            app.getSharedPreferences("stack-calibration-selection-${library.name}", 0).edit().clear().commit()
        }
    }

    private fun recording(folder: File, count: Int): File = File(folder, "stars.ser").also { file ->
        val input = frame(0)
        SERWriter(file).apply {
            open(width, height, PixelFormat.MONO16)
            try { repeat(count) { writeFrame(input.copy(frameId = it.toLong(), timestamp = it * 250_000L)) } } finally { close() }
        }
    }

    private fun frame(id: Long): FrameData {
        val random = Random(1234)
        val stars = mutableListOf<Triple<Double, Double, Double>>()
        while (stars.size < 28) {
            val p = Triple(14 + random.nextDouble() * (width - 28), 14 + random.nextDouble() * (height - 28), .15 + random.nextDouble() * .4)
            if (stars.none { hypot(it.first - p.first, it.second - p.second) < 12 }) stars += p
        }
        val data = ByteArray(width * height * 2)
        for (y in 0 until height) for (x in 0 until width) {
            var signal = .04
            for ((sx, sy, amplitude) in stars) signal += amplitude * exp(-((x - sx).pow(2) + (y - sy).pow(2)) / (2 * 1.2 * 1.2))
            val value = (signal * 65535).roundToInt().coerceIn(0, 65535)
            val p = (y * width + x) * 2
            data[p] = value.toByte(); data[p + 1] = (value ushr 8).toByte()
        }
        return FrameData(data, width, height, PixelFormat.MONO16, id, id * 250_000)
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(60_000) { while (!condition()) delay(20) }
    private suspend fun settle(coordinator: StackingCoordinator) = await { !coordinator.rendering.value && !coordinator.hasPlanetaryWork() }
    private suspend fun phase(coordinator: StackingCoordinator, expected: StackTaskPhase) {
        await { !coordinator.hasPlanetaryWork() && (coordinator.task.value.phase == expected || coordinator.task.value.phase == StackTaskPhase.FAILED) }
        assertEquals(coordinator.task.value.message, expected, coordinator.task.value.phase)
    }
}
