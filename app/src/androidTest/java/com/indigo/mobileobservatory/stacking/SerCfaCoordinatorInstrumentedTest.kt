package com.indigo.mobileobservatory.stacking

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SERWriter
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class SerCfaCoordinatorInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test fun rawOverrideRebuildsPreviewInvalidatesAnalysisAndExportsEffectiveLayout() = runBlocking {
        workspace { c, folder, exports ->
            val file = recording(folder, "unmarked-light.ser")
            val original = hash(file)
            main { c.open(StackMode.PLANETARY); c.loadFile(file) }
            phase(c, StackTaskPhase.READY)
            val physicalIdentity = c.task.value.source!!.identity
            val autoIdentity = c.task.value.source!!.decodingIdentity
            assertEquals(1, c.task.value.source!!.channels)
            assertFalse(c.task.value.source!!.isBayer)
            settle(c)
            main { c.setSerCfaInterpretation(SerCfaInterpretation.RGGB) }
            phase(c, StackTaskPhase.READY)
            assertEquals(physicalIdentity, c.task.value.source!!.identity)
            assertNotEquals(autoIdentity, c.task.value.source!!.decodingIdentity)
            assertEquals("RGGB", c.task.value.source!!.effectiveLayout)
            assertTrue(c.task.value.source!!.isBayer)
            assertEquals(3, c.task.value.source!!.channels)
            assertNotNull(c.preview.value)
            main { c.analyze(null, false); c.setSerCfaInterpretation(SerCfaInterpretation.BGGR) }
            assertEquals("Busy tasks retain their decode", SerCfaInterpretation.RGGB, c.serCfaInterpretation.value)
            phase(c, StackTaskPhase.ANALYZED)
            settle(c)
            main { c.setSerCfaInterpretation(SerCfaInterpretation.BGGR) }
            phase(c, StackTaskPhase.READY)
            assertEquals(0, c.task.value.histogram.size)
            main { c.stack(PlanetarySelection(count = 1)) }
            phase(c, StackTaskPhase.FAILED)
            assertEquals("analyze_first", c.task.value.message)
            for (algorithm in DemosaicAlgorithm.values()) {
                settle(c)
                main {
                    c.setDemosaicAlgorithm(algorithm)
                    if (!c.hasPlanetaryWork() && c.task.value.phase != StackTaskPhase.READY) c.loadFile(file)
                }
                phase(c, StackTaskPhase.READY)
                main { c.analyze(null, false) }; phase(c, StackTaskPhase.ANALYZED)
                main { c.stack(PlanetarySelection(count = 1)) }; phase(c, StackTaskPhase.COMPLETE)
                main { c.selectPreset(ProcessingPreset.RAW); c.export() }
                await { c.task.value.exportedFiles.size == 3 && !c.hasPlanetaryWork() }
                val output = c.task.value.exportedFiles.map(::File); exports += output
                val fits = output.single { it.extension == "fits" }
                val metadata = StackFitsReader.readMetadata(fits)
                assertEquals("BGGR", metadata["cfaInterpretation"])
                assertEquals("BGGR", metadata["effectiveSensorLayout"])
                assertEquals("0", metadata["headerColorId"])
                assertEquals(algorithm.name, metadata["demosaicAlgorithm"])
                assertTrue(metadata.getValue("decodingIdentity").contains("cfa:BGGR"))
                val json = JSONObject(output.single { it.extension == "json" }.readText())
                assertEquals("BGGR", json.getString("cfaInterpretation"))
                val expected = SERReader(file, cfaInterpretation = SerCfaInterpretation.BGGR).use { reader ->
                    reader.sampleAlignment = SampleAlignment.RIGHT; check(reader.open())
                    reader.readLinearFrame(0, algorithm = algorithm)!!
                }
                val actual = StackFitsReader.read(fits)
                for (y in 4 until 92) for (x in 4 until 124) for (channel in 0..2) {
                    val i = (y * 128 + x) * 3 + channel
                    assertEquals("$algorithm $x,$y,$channel", expected.pixels[i], actual.pixels[i], 1e-5f)
                }
            }
            settle(c)
            main { c.setSerCfaInterpretation(SerCfaInterpretation.MONO) }
            phase(c, StackTaskPhase.READY)
            assertEquals(1, c.task.value.source!!.channels)
            assertFalse(c.task.value.source!!.isBayer)
            assertFalse(c.hasResult(StackMode.PLANETARY))
            assertArrayEquals(original, hash(file))
        }
    }

    @Test fun unknownRawHeaderCanBeRecoveredAndMastersMatchTheEffectiveMosaic() = runBlocking {
        workspace { c, folder, cleanup ->
            val bias = recording(folder, "bias.ser", bias = true)
            main { c.calibration.createMaster(CalibrationType.BIAS, bias, true, SerCfaInterpretation.RGGB) }
            await { !c.calibration.state.value.busy && c.calibration.state.value.masters.isNotEmpty() }
            val master = c.calibration.state.value.masters.single()
            assertEquals("RGGB", master.geometry.layout)
            assertTrue(master.sourceIdentity.contains("cfa:RGGB"))
            main { c.calibration.selectMaster(CalibrationType.BIAS, master.id, true) }
            await { !c.calibration.state.value.busy }
            val file = recording(folder, "missing-pattern.ser")
            RandomAccessFile(file, "rw").use { it.seek(18); it.writeInt(Integer.reverseBytes(7)) }
            val original = hash(file)
            main { c.open(StackMode.PLANETARY); c.loadFile(file) }
            phase(c, StackTaskPhase.FAILED)
            assertEquals("cfa_unknown_header", c.task.value.message)
            main { c.setSerCfaInterpretation(SerCfaInterpretation.RGGB) }
            phase(c, StackTaskPhase.READY)
            assertEquals("RGGB", c.task.value.source!!.effectiveLayout)
            settle(c)
            main { c.setSerCfaInterpretation(SerCfaInterpretation.BGGR) }
            phase(c, StackTaskPhase.FAILED)
            assertEquals("calibration_geometry_mismatch", c.task.value.message)
            assertNull("An incompatible master cannot leave the old preview visible", c.preview.value)
            assertFalse(c.hasResult(StackMode.PLANETARY))
            assertArrayEquals(original, hash(file))
            main { c.clearPlanetary() }; phase(c, StackTaskPhase.EMPTY)
            main { c.setSerCfaInterpretation(SerCfaInterpretation.AUTO); c.importFile(Uri.fromFile(file)) }
            phase(c, StackTaskPhase.FAILED)
            assertEquals("cfa_unknown_header", c.task.value.message)
            main { c.setSerCfaInterpretation(SerCfaInterpretation.RGGB) }
            phase(c, StackTaskPhase.READY)
            val retainedImport = c.task.value.source!!.file
            cleanup += retainedImport
            assertNotEquals(file.canonicalPath, retainedImport.canonicalPath)
            assertArrayEquals(original, hash(retainedImport))
        }
    }

    @Test fun ordinaryBayerPlaybackPreservesVisibleLightByDefaultAndInfraredIsExplicit() {
        val folder = File(app.cacheDir, "cfa-preview-${System.nanoTime()}").apply { mkdirs() }
        try {
            val file = recording(folder, "visible-light.ser")
            SERReader(file, cfaInterpretation = SerCfaInterpretation.RGGB).use { reader ->
                reader.sampleAlignment = SampleAlignment.RIGHT; assertTrue(reader.open())
                assertFalse(reader.lp750Enabled)
                val science = reader.readLinearFrame(0)!!.pixels
                val ordinary = reader.readPreviewBitmap(0)!!
                val pixel = ordinary.getPixel(64, 48)
                assertTrue((pixel ushr 16 and 255) > (pixel and 255))
                reader.lp750Enabled = true
                val infrared = reader.readPreviewBitmap(0)!!
                assertNotEquals(pixel, infrared.getPixel(64, 48))
                assertArrayEquals("Display infrared conversion cannot alter scientific samples", science, reader.readLinearFrame(0)!!.pixels, 0f)
                ordinary.recycle(); infrared.recycle()
            }
        } finally { folder.deleteRecursively() }
    }

    private suspend fun workspace(block: suspend (StackingCoordinator, File, MutableList<File>) -> Unit) {
        val folder = File(app.cacheDir, "cfa-coordinator-${System.nanoTime()}").apply { mkdirs() }
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

    private fun recording(folder: File, name: String, bias: Boolean = false): File {
        val width = 128; val height = 96
        val bytes = ByteArray(width * height * 2)
        for (y in 0 until height) for (x in 0 until width) {
            val radius = hypot((x - 64.0) / 44, (y - 48.0) / 32)
            val signal = if (bias) .02 else if (radius < 1) .30 + .08 * sin(x * .27) * cos(y * .31) + .04 * cos((x + y) * .51) else .008
            val color = if (y % 2 == 0 && x % 2 == 0) 0 else if (y % 2 == 1 && x % 2 == 1) 2 else 1
            val value = (signal * doubleArrayOf(1.4, 1.0, .6)[color] * 65535).roundToInt()
            val p = (y * width + x) * 2
            bytes[p] = value.toByte(); bytes[p + 1] = (value ushr 8).toByte()
        }
        return File(folder, name).also { file ->
            SERWriter(file).apply {
                open(width, height, PixelFormat.MONO16)
                repeat(2) { writeFrame(FrameData(bytes, width, height, PixelFormat.MONO16, it.toLong(), it * 100_000L)) }
                close()
            }
        }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(30_000) { while (!condition()) delay(20) }
    private suspend fun settle(c: StackingCoordinator) = await { !c.rendering.value && !c.hasPlanetaryWork() }
    private suspend fun phase(c: StackingCoordinator, phase: StackTaskPhase) {
        await { !c.hasPlanetaryWork() && (c.task.value.phase == phase || c.task.value.phase == StackTaskPhase.FAILED) }
        assertEquals(c.task.value.message, phase, c.task.value.phase)
    }
}
