package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.PSERWriter
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SERWriter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.*

/** Real disk-format to science-result integration; independent FITS reader is deliberately local to this test. */
class PlanetaryPipelineIntegrationTest {
    private val width = 128
    private val height = 96
    private val roi = PixelRoi(10, 6, 108, 84)

    private fun scene(x: Double, y: Double): Double {
        val radius = hypot(x - 63.5, y - 47.5)
        val disk = (1 - tanh((radius - 31) / .9)) * .5
        return .013 + x / 800_000 + disk * (.38 + .035 * sin(y * .51) + .027 * sin(x * .61 + y * .23) +
            .06 * exp(-((x - 72).pow(2) / 11 + (y - 38).pow(2) / 8)))
    }

    private fun samples(frame: Int): FloatArray {
        val dx = listOf(0.0, 1.37, -.81, .4, -1.2, 1.6)[frame]
        val dy = listOf(0.0, -.61, .32, -.4, .8, -1.3)[frame]
        var values = FloatArray(width * height) { p ->
            val x = (p % width).toDouble(); val y = (p / width).toDouble()
            scene(x - dx - if (frame == 1) .7 * sin(y / 22) else 0.0, y - dy).toFloat()
        }
        repeat(listOf(0, 0, 1, 3, 5, 8)[frame]) {
            val previous = values
            values = FloatArray(previous.size) { p ->
                val x = p % width; val y = p / width
                var sum = 0.0
                for (oy in -1..1) for (ox in -1..1) sum += previous[(y + oy).coerceIn(0, height - 1) * width + (x + ox).coerceIn(0, width - 1)]
                (sum / 9).toFloat()
            }
        }
        return values
    }

    /** RGB48 SER and 12-bit RGGB packed PSER exercise different physical storage and color paths. */
    private fun recording(file: File, packed: Boolean) {
        val format = if (packed) PixelFormat.BAYER_RG12 else PixelFormat.RGB48
        val max = if (packed) 4095 else 65535
        fun input(index: Int): FrameData {
            val linear = samples(index)
            val bytes = ByteBuffer.allocate(width * height * format.bytesPerPixel).order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until height) for (x in 0 until width) {
                val value = linear[y * width + x]
                if (packed) {
                    val gain = if (y % 2 == 0 && x % 2 == 0) 1.12 else if (y % 2 == 1 && x % 2 == 1) .86 else 1.0
                    bytes.putShort((value * gain * max).roundToInt().toShort())
                } else for (gain in listOf(1.12, 1.0, .86)) bytes.putShort((value * gain * max).roundToInt().toShort())
            }
            return FrameData(bytes.array(), width, height, format, index.toLong(), index * 20_000L)
        }
        if (packed) {
            val writer = PSERWriter(file)
            try { writer.open(width, height, format, "Synthetic high-bit color"); repeat(6) { writer.writeFrame(input(it)) } }
            finally { writer.close() }
        } else {
            val writer = SERWriter(file)
            try { writer.open(width, height, format, "Synthetic high-bit color"); repeat(6) { writer.writeFrame(input(it)) } }
            finally { writer.close() }
        }
    }

    private class FileSource(file: File) : PlanetaryFrameSource {
        private val reader = SERReader(file).apply { check(open()) { lastError.orEmpty() } }
        private val header = reader.header!!
        override val frameCount = header.frameCount.toLong()
        override val width = header.width
        override val height = header.height
        override val channels = if (header.colorId == 0) 1 else 3
        var closed = false
        override fun readFrame(index: Long): LinearImage = checkNotNull(reader.readLinearFrame(Math.toIntExact(index)))
        override fun exposureSeconds(index: Long) = .02
        override fun close() { reader.close(); closed = true }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun realSerAndPserToMultiPointStackRecipeAndIndependentFloatFits() {
        val directory = Files.createTempDirectory("planetary-e2e-").toFile()
        val artifacts = File("build/reports/stacking/integration").apply { mkdirs() }
        try {
            for (packed in listOf(false, true)) {
                val sourceFile = File(directory, if (packed) "color12.pser" else "color16.ser")
                recording(sourceFile, packed)
                val sourceHash = hash(sourceFile)
                val cache = File(directory, "cache-${packed}").apply { mkdirs() }
                val options = PlanetaryOptions(roi = roi, alignmentPatchSize = 16, maxGlobalShift = 12, maxAlignmentPoints = 96)
                val progress = mutableSetOf<PlanetaryPhase>()
                val stacker = PlanetaryStacker()
                val result = FileSource(sourceFile).use { source ->
                    val decoded = source.readFrame(0)
                    assertEquals(3, decoded.channels)
                    assertTrue("Raw decoder must retain sub-8-bit levels", decoded.pixels.any { abs(it * 255 - (it * 255).roundToInt()) > .1 })
                    stacker.analyze(source, options, cache).use { analysis ->
                        assertEquals(roi, analysis.options.roi)
                        assertEquals(6L, analysis.validFrames)
                        assertTrue(analysis.referenceFrame in 0L..1L)
                        stacker.stack(source, analysis, PlanetarySelection(percent = 50.0),
                            PlanetaryControl(progress = { progress += it.phase }))
                    }
                }
                assertEquals(3L, result.stats.selectedFrames)
                assertEquals(3L, result.stats.stackedFrames)
                assertEquals(.06, result.stats.effectiveExposureSeconds, 1e-12)
                assertEquals(3L, result.stats.modeCounts[PlanetaryRegistrationMode.MULTIPOINT])
                assertTrue(progress.containsAll(listOf(PlanetaryPhase.READ, PlanetaryPhase.REGISTER, PlanetaryPhase.STACK, PlanetaryPhase.COMPLETE)))
                assertTrue(cache.listFiles()!!.isEmpty())
                val rawBefore = result.image.pixels.copyOf()
                val parameters = StackProcessingParameters.preset(ProcessingPreset.INTENSE).copy(
                    blackPoint = .005, whitePoint = .9, midtone = .31, curve = 45.0, brightness = 1.2,
                    sharpening = .6, redBalance = .95, blueBalance = 1.1, saturation = 1.2)
                val processed = StackPostProcessor.process(result.image, parameters)
                val serialized = StackResultMetadata.processing(processed).toString()
                val restored = StackResultMetadata.parseParameters(JSONObject(serialized).getJSONObject("parameters"))
                assertEquals(parameters, restored)
                assertArrayEquals(processed.image.pixels, StackPostProcessor.process(result.image, restored).image.pixels, 0f)
                assertArrayEquals(rawBefore, result.image.pixels, 0f)
                assertArrayEquals(rawBefore, StackPostProcessor.process(result.image, StackProcessingParameters.preset(ProcessingPreset.RAW)).image.pixels, 0f)
                val natural = StackPostProcessor.process(result.image, StackProcessingParameters.preset(ProcessingPreset.NATURAL)).image
                assertFalse(rawBefore.contentEquals(natural.pixels))
                assertFalse(natural.pixels.contentEquals(processed.image.pixels))
                assertTrue(processed.image.pixels.all { it.isFinite() && it in 0f..1f })
                val output = File(directory, "${sourceFile.nameWithoutExtension}-linear.fits")
                val metadata = mapOf("schema" to "indigo-stack-1", "source" to sourceFile.name, "sourceSha256" to sourceHash,
                    "frameCount" to result.stats.stackedFrames.toString(), "exposureSeconds" to result.stats.effectiveExposureSeconds.toString(),
                    "calibrated" to "false", "processing" to serialized, "roi" to "10,6,108,84", "keepPercent" to "50.0",
                    "modeCounts" to result.stats.modeCounts.toString(), "inputBits" to if (packed) "12" else "16")
                StackFitsWriter.write(output, result.image, metadata)
                val reloaded = readIndependentFits(output)
                assertArrayEquals(rawBefore, reloaded.first.pixels, 0f)
                assertEquals(3, reloaded.first.channels)
                assertEquals(sourceFile.name, reloaded.second.getString("source"))
                assertEquals(sourceHash, reloaded.second.getString("sourceSha256"))
                assertEquals("3", reloaded.second.getString("frameCount"))
                assertEquals("10,6,108,84", reloaded.second.getString("roi"))
                val savedRecipe = StackResultMetadata.parseParameters(JSONObject(reloaded.second.getString("processing")).getJSONObject("parameters"))
                assertArrayEquals(processed.image.pixels, StackPostProcessor.process(reloaded.first, savedRecipe).image.pixels, 0f)
                assertEquals("Input source must remain unchanged", sourceHash, hash(sourceFile))
                assertEquals(0L, output.length() % 2880L)
                sourceFile.copyTo(File(artifacts, sourceFile.name), overwrite = true)
                output.copyTo(File(artifacts, output.name), overwrite = true)
                File(artifacts, "${sourceFile.nameWithoutExtension}-recipe.json").writeText(JSONObject(metadata).toString(2))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationAndExportFailureCleanActualFilePipelineArtifactsAndCloseSource() {
        val directory = Files.createTempDirectory("planetary-e2e-failure-").toFile()
        try {
            val recording = File(directory, "source.pser")
            recording(recording, true)
            val originalHash = hash(recording)
            val cache = File(directory, "quality").apply { mkdirs() }
            val stacker = PlanetaryStacker()
            val source = FileSource(recording)
            var checks = 0
            assertThrows(CancellationException::class.java) {
                source.use { stacker.analyze(it, PlanetaryOptions(roi = roi), cache,
                    PlanetaryControl(checkCancelled = { if (++checks == 100) throw CancellationException("cancel analysis") })) }
            }
            assertTrue(source.closed)
            assertTrue(cache.listFiles()!!.isEmpty())
            val result = FileSource(recording).use { reopened ->
                stacker.analyze(reopened, PlanetaryOptions(roi = roi), cache).use { analysis ->
                    var registered = false
                    assertThrows(CancellationException::class.java) {
                        stacker.stack(reopened, analysis, PlanetarySelection(count = 3), PlanetaryControl(
                            checkCancelled = { if (registered) throw CancellationException("cancel registration") },
                            progress = { if (it.phase == PlanetaryPhase.REGISTER) registered = true }))
                    }
                    stacker.stack(reopened, analysis, PlanetarySelection(count = 1))
                }
            }
            val export = File(directory, "exports").apply { mkdirs() }
            val target = File(export, "linear.fits")
            var rows = 0
            assertThrows(CancellationException::class.java) {
                StackFitsWriter.write(target, result.image, mapOf("source" to recording.name)) {
                    if (++rows == 3) throw CancellationException("cancel export after writing rows")
                }
            }
            assertFalse(target.exists()); assertTrue(export.listFiles()!!.isEmpty())
            val bad = result.image.deepCopy().apply { pixels[width * 5 * channels] = Float.NaN }
            assertThrows(IllegalArgumentException::class.java) { StackFitsWriter.write(target, bad) }
            assertFalse(target.exists()); assertTrue(export.listFiles()!!.isEmpty())
            assertTrue(cache.listFiles()!!.isEmpty())
            assertEquals(originalHash, hash(recording))
        } finally { directory.deleteRecursively() }
    }

    /** No StackFitsReader calls: independently parse FITS cards, big-endian planes and metadata extension. */
    private fun readIndependentFits(file: File): Pair<LinearImage, JSONObject> = RandomAccessFile(file, "r").use { input ->
        fun header(): Map<String, String> {
            val values = linkedMapOf<String, String>()
            while (true) {
                val card = ByteArray(80); input.readFully(card)
                val text = card.toString(Charsets.US_ASCII)
                val key = text.substring(0, 8).trim()
                if (key == "END") break
                values[key] = text.substring(10).trim().trim('\'').trim()
            }
            input.seek(((input.filePointer + 2879) / 2880) * 2880)
            return values
        }
        val h = header()
        assertEquals("T", h["SIMPLE"]); assertEquals("-32", h["BITPIX"])
        val w = h.getValue("NAXIS1").toInt(); val ht = h.getValue("NAXIS2").toInt()
        val channels = h["NAXIS3"]?.toInt() ?: 1
        val interleaved = FloatArray(w * ht * channels)
        for (channel in 0 until channels) for (pixel in 0 until w * ht) interleaved[pixel * channels + channel] = input.readFloat()
        input.seek(((input.filePointer + 2879) / 2880) * 2880)
        val extension = header()
        assertEquals("STACKMETA", extension["EXTNAME"])
        val bytes = ByteArray(extension.getValue("NAXIS1").toInt()); input.readFully(bytes)
        LinearImage(w, ht, channels, interleaved) to JSONObject(bytes.toString(Charsets.UTF_8))
    }
}
