package com.indigo.mobileobservatory.stacking

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SERWriter
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class DemosaicCoordinatorInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test fun everyBayerAlgorithmIsFixedThroughAnalysisStackAndFitsAndChangingItInvalidatesAnalysis() = runBlocking {
        workspace { c, folder, exports ->
            assertEquals(DemosaicAlgorithm.DDFAPD, c.demosaicAlgorithm.value)
            val file = recording(folder, PixelFormat.BAYER_RG16)
            val original = hash(file)
            for (algorithm in DemosaicAlgorithm.values()) {
                await { !c.rendering.value && !c.hasPlanetaryWork() }
                main { c.setDemosaicAlgorithm(algorithm) }
                assertEquals(algorithm, c.demosaicAlgorithm.value)
                assertFalse(c.hasResult(StackMode.PLANETARY))
                assertEquals(0L, c.task.value.qualityFrames)
                if (c.task.value.source == null && !c.hasPlanetaryWork()) main { c.open(StackMode.PLANETARY); c.loadFile(file) }
                awaitPhase(c, StackTaskPhase.READY)
                assertTrue(c.task.value.source!!.isBayer)
                // A completed old analysis must not remain usable after decoder selection.
                main { c.stack(PlanetarySelection(count = 1)) }
                awaitPhase(c, StackTaskPhase.FAILED)
                assertEquals("analyze_first", c.task.value.message)
                main {
                    c.analyze(PixelRoi(4, 4, 120, 88), false)
                    c.setDemosaicAlgorithm(DemosaicAlgorithm.values().first { it != algorithm })
                }
                assertEquals("Busy analysis cannot change its decoder", algorithm, c.demosaicAlgorithm.value)
                awaitPhase(c, StackTaskPhase.ANALYZED)
                main { c.stack(PlanetarySelection(count = 1)) }
                awaitPhase(c, StackTaskPhase.COMPLETE)
                main { c.selectPreset(ProcessingPreset.RAW); c.export() }
                await { c.task.value.exportedFiles.size == 3 && !c.hasPlanetaryWork() }
                val batch = c.task.value.exportedFiles.map(::File); exports += batch
                val fits = batch.single { it.extension == "fits" }
                val metadata = StackFitsReader.readMetadata(fits)
                assertEquals(algorithm.name, metadata["demosaicAlgorithm"])
                assertEquals(algorithm.name, JSONObject(batch.single { it.extension == "json" }.readText()).getString("demosaicAlgorithm"))
                assertEquals(LinearPixelDecoder.demosaicExtraBytes(128, 96, PixelFormat.BAYER_RG16, algorithm),
                    metadata.getValue("demosaicScratchBytes").toLong())
                val expected = SERReader(file).use { reader ->
                    reader.sampleAlignment = SampleAlignment.RIGHT; check(reader.open())
                    reader.readLinearFrame(0, algorithm = algorithm)!!
                }
                val actual = StackFitsReader.read(fits)
                for (y in 4 until 92) for (x in 4 until 124) for (channel in 0..2) {
                    val index = (y * 128 + x) * 3 + channel
                    assertEquals("$algorithm ($x,$y,$channel)", expected.pixels[index], actual.pixels[index], 0.00001f)
                }
            }
            assertArrayEquals(original, hash(file))
        }
    }

    @Test fun monoAndRgbBypassInterpolationAndExportNone() = runBlocking {
        workspace { c, folder, exports ->
            for (format in listOf(PixelFormat.MONO16, PixelFormat.RGB48)) {
                val file = recording(folder, format)
                main { c.open(StackMode.PLANETARY); c.loadFile(file) }
                awaitPhase(c, StackTaskPhase.READY)
                assertFalse(c.task.value.source!!.isBayer)
                main { c.analyze(null, false) }; awaitPhase(c, StackTaskPhase.ANALYZED)
                main { c.stack(PlanetarySelection(count = 1)) }; awaitPhase(c, StackTaskPhase.COMPLETE)
                main { c.selectPreset(ProcessingPreset.RAW); c.export() }
                await { c.task.value.exportedFiles.size == 3 && !c.hasPlanetaryWork() }
                val batch = c.task.value.exportedFiles.map(::File); exports += batch
                val metadata = StackFitsReader.readMetadata(batch.single { it.extension == "fits" })
                assertEquals("NONE", metadata["demosaicAlgorithm"])
                assertEquals("0", metadata["demosaicScratchBytes"])
                assertEquals("NONE", JSONObject(batch.single { it.extension == "json" }.readText()).getString("demosaicAlgorithm"))
            }
        }
    }

    @Test fun liveDecoderChangeRequiresResetIncludingPauseAndScratchIsBudgetedBeforeStart() = runBlocking {
        workspace { c, _, _ ->
            val key = StackSessionKey("demosaic-test", 128, 96, PixelFormat.BAYER_RG16, Roi(0, 0, 128, 96),
                1, 1, 100_000.0, 10f, "L", "raw")
            for (algorithm in DemosaicAlgorithm.values()) {
                main { c.setDemosaicAlgorithm(algorithm); c.startLive(key.copy(demosaicAlgorithm = algorithm)) }
                assertEquals(LiveStackPhase.RUNNING, c.live.state.value.phase)
                val other = DemosaicAlgorithm.values().first { it != algorithm }
                main { c.setDemosaicAlgorithm(other) }
                assertEquals(algorithm, c.demosaicAlgorithm.value)
                assertEquals("reset_live_first", c.task.value.message)
                main { c.live.pause() }
                await { c.live.state.value.phase == LiveStackPhase.PAUSED }
                main { c.setDemosaicAlgorithm(other) }
                assertEquals(algorithm, c.demosaicAlgorithm.value)
                main { c.resetLive() }
            }
            val side = (128..2048 step 8).first { size ->
                val basic = DeepSkyStacker.estimateWorkingBytes(size, size, 3)
                basic <= c.memoryBudgetBytes && basic + LinearPixelDecoder.demosaicExtraBytes(size, size, key.format,
                    DemosaicAlgorithm.DDFAPD) > c.memoryBudgetBytes
            }
            val large = key.copy(width = side, height = side, roi = Roi(0, 0, side, side))
            main { c.setDemosaicAlgorithm(DemosaicAlgorithm.DDFAPD); c.startLive(large) }
            assertEquals(LiveStackPhase.EMPTY, c.live.state.value.phase)
            assertEquals("memory_budget", c.task.value.message)
            main { c.setDemosaicAlgorithm(DemosaicAlgorithm.BILINEAR); c.startLive(large.copy(demosaicAlgorithm = DemosaicAlgorithm.BILINEAR)) }
            assertEquals(LiveStackPhase.RUNNING, c.live.state.value.phase)
        }
    }

    @Test fun decoderBudgetFailureRetainsBayerSourceSoLoadAndImportCanRetryWithBilinear() = runBlocking {
        workspace { c, folder, cleanup ->
            val side = (128..2048 step 8).first { size ->
                val basic = PlanetaryStacker.estimatedPeakBytes(size, size, 3)
                basic <= c.memoryBudgetBytes && basic + LinearPixelDecoder.demosaicExtraBytes(
                    size, size, PixelFormat.BAYER_RG16, DemosaicAlgorithm.DDFAPD) > c.memoryBudgetBytes
            }
            val file = recording(folder, PixelFormat.BAYER_RG16, side, side, 1)
            val original = hash(file)
            for (imported in listOf(false, true)) {
                main { c.clearPlanetary() }
                awaitPhase(c, StackTaskPhase.EMPTY)
                main {
                    c.setDemosaicAlgorithm(DemosaicAlgorithm.DDFAPD)
                    c.open(StackMode.PLANETARY)
                    if (imported) c.importFile(Uri.fromFile(file)) else c.loadFile(file)
                }
                awaitPhase(c, StackTaskPhase.FAILED)
                assertEquals("memory_budget", c.task.value.message)
                val retained = checkNotNull(c.task.value.source)
                assertTrue("Bayer selector must remain available after a decoder budget failure", retained.isBayer)
                assertTrue(retained.file.isFile)
                assertEquals(side, retained.width)
                if (imported) {
                    cleanup += retained.file
                    assertNotEquals(file.canonicalPath, retained.file.canonicalPath)
                    assertArrayEquals(original, hash(retained.file))
                }
                // Changing the algorithm alone must reload the retained source; no new picker.
                main { c.setDemosaicAlgorithm(DemosaicAlgorithm.BILINEAR) }
                awaitPhase(c, StackTaskPhase.READY)
                assertEquals(DemosaicAlgorithm.BILINEAR, c.demosaicAlgorithm.value)
                assertEquals(retained.identity, c.task.value.source!!.identity)
                assertNotNull(c.preview.value)
            }
            assertArrayEquals(original, hash(file))
        }
    }

    private suspend fun workspace(block: suspend (StackingCoordinator, File, MutableList<File>) -> Unit) {
        val folder = File(app.cacheDir, "demosaic-integration-${System.nanoTime()}").apply { mkdirs() }
        val library = File(folder, "masters-${System.nanoTime()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val c = StackingCoordinator(app, scope, calibrationDirectory = library)
        val exports = mutableListOf<File>()
        try { await { c.calibration.state.value.initialized }; block(c, folder, exports) }
        finally {
            main { c.close() }; scope.cancel(); exports.forEach { it.delete() }; folder.deleteRecursively()
            app.getSharedPreferences("stack-calibration-selection-${library.name}", 0).edit().clear().commit()
        }
    }
    private fun recording(directory: File, format: PixelFormat, width: Int = 128, height: Int = 96,
                          frames: Int = 2): File {
        val channels = if (format.isRgb) 3 else 1
        val bytes = ByteArray(width * height * channels * 2)
        for (y in 0 until height) for (x in 0 until width) {
            val radius = hypot((x - width / 2.0) / (width * 44.0 / 128), (y - height / 2.0) / (height / 3.0))
            val signal = if (radius < 1) .30 + .08 * sin(x * .27) * cos(y * .31) + .04 * cos((x + y) * .51) else .008
            for (channel in 0 until channels) {
                val color = if (format.isBayer) { if (y % 2 == 0 && x % 2 == 0) 0 else if (y % 2 == 1 && x % 2 == 1) 2 else 1 } else channel
                val value = (signal * (if (format.isMono) 1.0 else doubleArrayOf(1.15, 1.0, .82)[color]) * 65535).roundToInt()
                val p = ((y * width + x) * channels + channel) * 2
                bytes[p] = value.toByte(); bytes[p + 1] = (value ushr 8).toByte()
            }
        }
        return File(directory, "${format.name}.ser").also { file ->
            SERWriter(file).apply {
                open(width, height, format)
                repeat(frames) { writeFrame(FrameData(bytes, width, height, format, it.toLong(), it * 100_000L)) }
                close()
            }
        }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(30_000) { while (!condition()) delay(20) }
    private suspend fun awaitPhase(c: StackingCoordinator, phase: StackTaskPhase) {
        await { !c.hasPlanetaryWork() && (c.task.value.phase == phase || c.task.value.phase == StackTaskPhase.FAILED) }
        assertEquals(c.task.value.message, phase, c.task.value.phase)
    }
}
