package com.indigo.mobileobservatory.stacking

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

/** Same native geometry as the reported 585C video, with known synthetic pixels. */
@RunWith(AndroidJUnit4::class)
class OfflineRegionInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    @Test fun full585ColourCanPreviewAnalyzeStackAndExportWithBin4() = runBlocking {
        workspace { c, dir, cleanup ->
            val source = recording(dir, false)
            main { c.open(StackMode.PLANETARY); c.loadFile(source) }; phase(c, StackTaskPhase.READY)
            assertNotNull(c.sourcePreview.value)
            assertTrue(c.sourcePreview.value!!.width.toLong() * c.sourcePreview.value!!.height <= 262_144)
            assertNotNull(c.offlinePlan()!!.issue(c.memoryBudgetBytes))
            assertNull(c.offlinePlan(softwareBin = 4)!!.issue(c.memoryBudgetBytes))
            main { c.analyze(null, false, 4) }; phase(c, StackTaskPhase.ANALYZED)
            main { c.stack(PlanetarySelection(count = 1)) }; phase(c, StackTaskPhase.COMPLETE)
            assertEquals(1L, c.task.value.stats!!.stackedFrames)
            val evidence = JSONObject().put("fixture", "3856x2180 RAW16 RGGB, synthetic planet")
                .put("heapLimitBytes", c.heapLimitBytes).put("budgetBytes", c.memoryBudgetBytes)
                .put("estimatedBytes", c.task.value.stats!!.estimatedPeakBytes)
                .put("javaHeapUsedPeakBytes", c.task.value.javaPeakBytes).put("nativeHeapUsedPeakBytes", c.task.value.nativePeakBytes)
                .put("stackElapsedMillis", c.task.value.stats!!.elapsedMillis)
            File(app.getExternalFilesDir(null), "offline-region-measurement.json").writeText(evidence.toString(2))
            main { c.selectPreset(ProcessingPreset.RAW) }; settled(c)
            main { c.export() }; phase(c, StackTaskPhase.COMPLETE)
            val files = c.task.value.exportedFiles.map(::File); cleanup += files
            val fits = files.single { it.extension == "fits" }
            val image = StackFitsReader.read(fits)
            assertEquals(964, image.width); assertEquals(545, image.height)
            val metadata = StackFitsReader.readMetadata(fits)
            assertEquals("4", metadata["softwareBin"])
            assertEquals("[0,0,3856,2180]", metadata["sourceRegion"])
            val p = (272 * image.width + 482) * 3
            assertTrue(image.pixels[p] > image.pixels[p + 1]); assertTrue(image.pixels[p + 1] > image.pixels[p + 2])
        }
    }

    @Test fun actualCropAndFullFieldDeepSkyUseTheirProcessingDimensions() = runBlocking {
        workspace { c, dir, _ ->
            val planet = recording(dir, false)
            main { c.open(StackMode.PLANETARY); c.loadFile(planet) }; phase(c, StackTaskPhase.READY)
            val roi = PixelRoi(1568, 730, 720, 720)
            assertNull(c.offlinePlan(roi)!!.issue(c.memoryBudgetBytes))
            main { c.analyze(roi, false) }; phase(c, StackTaskPhase.ANALYZED)
            main { c.stack(PlanetarySelection(count = 1)) }; phase(c, StackTaskPhase.COMPLETE)
            assertEquals(1L, c.task.value.stats!!.stackedFrames)
            main { c.clearPlanetary() }; phase(c, StackTaskPhase.EMPTY)
            val stars = recording(dir, true)
            main { assertTrue(c.setTarget(StackMode.PLANETARY, StackTarget.DEEP_SKY)); c.loadFile(stars) }
            phase(c, StackTaskPhase.READY)
            assertNull(c.offlinePlan(softwareBin = 4)!!.issue(c.memoryBudgetBytes))
            main { c.stackDeepSky(softwareBin = 4) }; phase(c, StackTaskPhase.COMPLETE)
            assertEquals(1, c.task.value.deepSkyStats!!.stack.accepted)
            assertTrue(c.hasResult(StackMode.PLANETARY))
        }
    }

    @Test fun multiFrameDeepSkyBin2MatchesAdmissionAndExportsTransferredPixelsWithFilters() = runBlocking {
        workspace { c, dir, cleanup ->
            val stars = recording(dir, true, frames = 3)
            main { c.open(StackMode.PLANETARY); assertTrue(c.setTarget(StackMode.PLANETARY, StackTarget.DEEP_SKY)); c.loadFile(stars) }
            phase(c, StackTaskPhase.READY)
            val plan = c.offlinePlan(softwareBin = 2)!!
            assertTrue(plan.workingBytes < 93L shl 20)
            assertNull(plan.issue(c.memoryBudgetBytes))
            main { c.stackDeepSky(softwareBin = 2) }; phase(c, StackTaskPhase.COMPLETE)
            val stats = c.task.value.deepSkyStats!!
            assertEquals(3, stats.stack.accepted)
            assertEquals(3L, stats.unknownExposureFrames)
            assertEquals(plan.workingBytes, stats.stack.estimatedWorkingBytes)
            main { c.updateProcessing(StackProcessingParameters.preset(ProcessingPreset.INTENSE).copy(sharpening = .3)) }
            settled(c)
            main { c.export() }; phase(c, StackTaskPhase.COMPLETE)
            val files = c.task.value.exportedFiles.map(::File); cleanup += files
            assertTrue(files.single { it.extension == "png" }.length() > 0)
            val fits = files.single { it.extension == "fits" }
            val image = StackFitsReader.read(fits)
            assertEquals(1928, image.width); assertEquals(1090, image.height); assertEquals(3, image.channels)
            assertTrue(image.pixels.all { it.isFinite() })
            assertTrue(image.pixels.maxOrNull()!! > .1f)
            val metadata = StackFitsReader.readMetadata(fits)
            assertEquals("[0,0,3856,2180]", metadata["sourceRegion"])
            assertEquals("2", metadata["softwareBin"])
            val memory = JSONObject(metadata["memoryEstimate"]!!)
            assertEquals(plan.workingBytes, memory.getLong("peakBytes"))
            assertEquals("transferred_accumulation", memory.getString("resultOwnership"))
            assertEquals(maxOf(memory.getLong("stackingBytes"), memory.getLong("previewBytes"),
                memory.getLong("exportBytes")), memory.getLong("peakBytes"))
        }
    }

    companion object {
    internal fun recording(directory: File, stars: Boolean, frames: Int = 1): File {
        val width = 3856; val height = 2180
        val file = File(directory, if (stars) "585-stars.ser" else "585-planet.ser")
        val header = ByteBuffer.allocate(178).order(ByteOrder.LITTLE_ENDIAN)
        header.put("LUCAM-RECORDER".toByteArray()); header.position(18)
        header.putInt(8); header.putInt(0); header.putInt(width); header.putInt(height); header.putInt(16); header.putInt(frames)
        RandomAccessFile(file, "rw").use { out ->
            out.write(header.array())
            val row = ByteArray(width * 2)
            repeat(frames) { for (y in 0 until height) {
                for (x in 0 until width) {
                    val signal = if (stars) {
                        val dx = (x + 200) % 400 - 200; val dy = (y + 150) % 300 - 150
                        .015 + .5 * exp(-(dx * dx + dy * dy) / 98.0)
                    } else {
                        val dx = x - 1928.0; val dy = y - 1090.0
                        if (dx * dx + dy * dy < 300.0 * 300) .35 + .07 * sin(x * .07) + .04 * cos(y * .1) else .015
                    }
                    val colour = if (y % 2 == 0 && x % 2 == 0) 1.2 else if (y % 2 == 1 && x % 2 == 1) .8 else 1.0
                    val value = (signal * colour * 65535).toInt()
                    row[x * 2] = value.toByte(); row[x * 2 + 1] = (value ushr 8).toByte()
                }
                out.write(row)
            } }
        }
        return file
    }
    }
    private suspend fun workspace(block: suspend (StackingCoordinator, File, MutableList<File>) -> Unit) {
        val dir = File(app.cacheDir, "offline-region-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val c = StackingCoordinator(app, scope, calibrationDirectory = File(dir, "masters"))
        val exports = mutableListOf<File>()
        try { await { c.calibration.state.value.initialized }; block(c, dir, exports) }
        finally { main { c.close() }; scope.cancel(); exports.forEach { it.delete() }; dir.deleteRecursively() }
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private suspend fun await(condition: () -> Boolean) = withTimeout(120_000) { while (!condition()) delay(30) }
    private suspend fun settled(c: StackingCoordinator) = await { !c.hasPlanetaryWork() && !c.rendering.value }
    private suspend fun phase(c: StackingCoordinator, expected: StackTaskPhase) {
        await { !c.hasPlanetaryWork() && !c.rendering.value && (c.task.value.phase == expected || c.task.value.phase == StackTaskPhase.FAILED) }
        assertEquals(c.task.value.message, expected, c.task.value.phase)
    }
}
