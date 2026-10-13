package com.indigo.mobileobservatory.stacking

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.*

class PlanetaryStackerTest {
    private val width = 160
    private val height = 128
    private val roi get() = PixelRoi(0, 0, width, height)

    /** Continuous ground truth (no implementation sampler used to generate subpixel translations). */
    private fun planet(x: Double, y: Double): Float {
        val radius = hypot((x - 79.5) / 1.1, y - 63.5)
        val limb = (1 - tanh((radius - 44) / .8)) * .5
        val belts = .045 * sin(y * .47) + .025 * sin(y * .23 + x * .14)
        val spot = .11 * exp(-((x - 88).pow(2) / 20 + (y - 57).pow(2) / 11))
        val texture = .025 * sin(x * .61 + y * .24) * cos(y * .49)
        return (.012 + limb * (.37 + belts + texture + spot + .08 * max(0.0, 1 - radius / 44))).toFloat()
    }

    private fun frame(dx: Double = 0.0, dy: Double = 0.0, deform: Boolean = false): LinearImage {
        return LinearImage(width, height, 1, FloatArray(width * height) { p ->
            val x = (p % width).toDouble(); val y = (p / width).toDouble()
            val localX = if (deform) 2.2 * sin((y - 64) / 19) else 0.0
            val localY = if (deform) 1.7 * sin((x - 80) / 21) else 0.0
            planet(x - dx - localX, y - dy - localY)
        })
    }

    private fun blur(image: LinearImage, iterations: Int): LinearImage {
        var pixels = image.pixels.copyOf()
        repeat(iterations) {
            val next = FloatArray(pixels.size)
            for (y in 0 until height) for (x in 0 until width) {
                var sum = 0.0
                for (dy in -1..1) for (dx in -1..1) sum += pixels[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
                next[y * width + x] = (sum / 9).toFloat()
            }
            pixels = next
        }
        return image.copy(pixels = pixels)
    }

    private class Source(private val images: List<LinearImage>, private val seconds: Double = .01) : PlanetaryFrameSource {
        override val width = images.first().width
        override val height = images.first().height
        override val channels = images.first().channels
        override val frameCount = images.size.toLong()
        var reads = 0
        var closed = false
        override fun readFrame(index: Long): LinearImage { check(!closed); reads++; return images[index.toInt()].deepCopy() }
        override fun exposureSeconds(index: Long) = seconds
        override fun close() { closed = true }
    }

    @Test fun qualityRanksKnownBlurAndRejectsBlackNoiseSaturationAndClipping() {
        val original = frame()
        val scores = (0..3).map { PlanetaryQualityEstimator.measure(blur(original, it * 2), roi) }
        scores.forEach { assertEquals(it.toString(), PlanetaryRejection.NONE, it.rejection) }
        assertTrue(scores.map { it.score }.toString(), scores.zipWithNext().all { (a, b) -> a.score > b.score })
        val black = original.copy(pixels = FloatArray(width * height))
        assertEquals(PlanetaryRejection.LOW_SIGNAL, PlanetaryQualityEstimator.measure(black, roi).rejection)
        val white = original.copy(pixels = FloatArray(width * height) { 1f })
        assertEquals(PlanetaryRejection.SATURATION, PlanetaryQualityEstimator.measure(white, roi).rejection)
        val random = Random(4)
        val noise = original.copy(pixels = FloatArray(width * height) { (.15 + random.nextGaussian() * .035).toFloat() })
        assertEquals(PlanetaryRejection.NOISE, PlanetaryQualityEstimator.measure(noise, roi).rejection)
        assertEquals(PlanetaryRejection.OUT_OF_FRAME, PlanetaryQualityEstimator.measure(frame(-65.0), roi).rejection)
        println("Planetary blur quality (0/2/4/6 passes): ${scores.map { it.score }}")
    }

    @Test fun qualityRoiIgnoresExtraneousBackgroundNoise() {
        val original = frame()
        val polluted = original.deepCopy()
        val random = Random(99)
        for (y in 0 until height) for (x in 0 until width) if (x < 18 || x > 140) polluted.pixels[y * width + x] = random.nextFloat()
        val targetRoi = PixelRoi(24, 10, 112, 108)
        assertEquals(PlanetaryQualityEstimator.measure(original, targetRoi).score, PlanetaryQualityEstimator.measure(polluted, targetRoi).score, 0.0)
    }

    @Test fun globalRegistrationMeasuresIndependentSubpixelGroundTruth() {
        val registration = PlanetaryRegistration(frame(), roi, PlanetaryOptions(localAlignment = false, maxGlobalShift = 16))
        val expected = listOf(2.371 to -1.763, -5.631 to 3.217, .537 to .617)
        val errors = expected.map { (dx, dy) ->
            val alignment = registration.align(frame(dx, dy))
            assertEquals(PlanetaryRejection.NONE, alignment.rejection)
            val error = hypot(alignment.global.dx - dx, alignment.global.dy - dy)
            assertTrue("Expected $dx,$dy got ${alignment.global}, error=$error", error < .22)
            error
        }
        println("Planetary global subpixel errors px: $errors")
    }

    private fun warped(source: LinearImage, alignment: PlanetaryAlignment): Pair<LinearImage, PlanetaryWarp> {
        val warp = PlanetaryWarp(width, height, alignment)
        val pixels = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            pixels[y * width + x] = PlanetarySampling.bilinear(source.pixels, width, height,
                x + warp.offset(warp.dx, x, y), y + warp.offset(warp.dy, x, y)).takeIf { it.isFinite() }?.toFloat() ?: 0f
        }
        return LinearImage(width, height, 1, pixels) to warp
    }

    private fun mse(expected: LinearImage, actual: LinearImage): Double {
        var error = 0.0; var count = 0
        for (y in 19 until 109) for (x in 26 until 134) { error += (expected.pixels[y * width + x] - actual.pixels[y * width + x]).toDouble().pow(2); count++ }
        return error / count
    }

    @Test fun localRegistrationImprovesDeformedDetailWithoutMeshSeams() {
        val reference = frame()
        val source = frame(1.25, -.75, true)
        val global = PlanetaryRegistration(reference, roi, PlanetaryOptions(localAlignment = false, maxGlobalShift = 12)).align(source)
        val local = PlanetaryRegistration(reference, roi, PlanetaryOptions(alignmentPatchSize = 20, maxGlobalShift = 12, maxLocalShift = 5, maxAlignmentPoints = 128)).align(source)
        assertEquals(local.toString(), PlanetaryRejection.NONE, local.rejection)
        assertEquals(PlanetaryRegistrationMode.MULTIPOINT, local.mode)
        assertTrue(local.local.size >= 12)
        val globalImage = warped(source, global).first
        val (localImage, mesh) = warped(source, local)
        assertFalse(mesh.folded)
        val globalError = mse(reference, globalImage)
        val localError = mse(reference, localImage)
        assertTrue("Local $localError should improve global $globalError", localError < globalError * .8)
        var seamError = 0.0; var seamCount = 0
        var otherError = 0.0; var otherCount = 0
        for (y in 20 until 108) for (x in 27 until 133) {
            val i = y * width + x
            val derivativeError = abs((localImage.pixels[i + 1] - localImage.pixels[i]) - (reference.pixels[i + 1] - reference.pixels[i]))
            if (x % mesh.step == 0) { seamError += derivativeError; seamCount++ } else { otherError += derivativeError; otherCount++ }
        }
        val seamRatio = (seamError / seamCount) / (otherError / otherCount)
        assertTrue("Mesh seam derivative error ratio $seamRatio", seamRatio < 1.5)
        val directory = File("build/reports/stacking").apply { mkdirs() }
        writePng(reference, File(directory, "planetary-reference.png"))
        writePng(source, File(directory, "planetary-distorted.png"))
        writePng(globalImage, File(directory, "planetary-global.png"))
        writePng(localImage, File(directory, "planetary-local.png"))
        File(directory, "planetary-metrics.txt").writeText("globalMSE=$globalError\nlocalMSE=$localError\nseamDerivativeRatio=$seamRatio\nAP=${local.local.size}/${local.pointCount}\noptions=patch20,maxGlobal12,maxLocal5,maxPoints128\n")
        println("Planetary global/local MSE $globalError / $localError; seam ratio $seamRatio; AP ${local.local.size}/${local.pointCount}")
    }

    @Test fun localAlignmentPreservesRingsPolarBeltsAndLimbSeparately() {
        fun scene(x: Double, y: Double): Float {
            val px = x - 80; val py = y - 64
            val radius = hypot(px, py)
            val disk = (1 - tanh((radius - 30) / .9)) * .5
            val ringRadius = hypot(px, py * 3.7 + px * .42)
            val rings = .12 * (tanh((ringRadius - 41) / .7) - tanh((ringRadius - 69) / .7)) *
                (1 - .3 * exp(-(ringRadius - 57).pow(2) / 2))
            val details = .035 * sin(py * .65) + .018 * sin(px * .55 + py * .35) +
                .07 * exp(-(px * px / 18 + (py + 24).pow(2) / 8))
            return (.01 + rings * (1 - disk) + disk * (.4 + details)).toFloat()
        }
        val expected = LinearImage(width, height, 1, FloatArray(width * height) { scene((it % width).toDouble(), (it / width).toDouble()) })
        val source = expected.copy(pixels = FloatArray(width * height) {
            val x = (it % width).toDouble(); val y = (it / width).toDouble()
            scene(x - 1.3 - 1.8 * sin((y - 64) / 22), y + .7 - 1.5 * sin((x - 80) / 25))
        })
        val globalAlignment = PlanetaryRegistration(expected, roi, PlanetaryOptions(localAlignment = false, maxGlobalShift = 12)).align(source)
        val localAlignment = PlanetaryRegistration(expected, roi, PlanetaryOptions(alignmentPatchSize = 20, maxGlobalShift = 12, maxAlignmentPoints = 128)).align(source)
        assertEquals(PlanetaryRejection.NONE, localAlignment.rejection)
        assertEquals(PlanetaryRegistrationMode.MULTIPOINT, localAlignment.mode)
        val global = warped(source, globalAlignment).first
        val local = warped(source, localAlignment).first
        val sums = Array(4) { DoubleArray(3) }
        for (y in 5 until height - 5) for (x in 5 until width - 5) {
            val px = x - 80.0; val py = y - 64.0
            val radius = hypot(px, py)
            val ringRadius = hypot(px, py * 3.7 + px * .42)
            val region = when {
                radius in 28.0..32.0 -> 0
                radius < 27 && abs(py) > 18 -> 1
                radius < 27 -> 2
                radius > 34 && ringRadius in 40.0..70.0 -> 3
                else -> continue
            }
            val i = y * width + x
            sums[region][0] += (global.pixels[i] - expected.pixels[i]).toDouble().pow(2)
            sums[region][1] += (local.pixels[i] - expected.pixels[i]).toDouble().pow(2)
            sums[region][2]++
        }
        val names = listOf("limb", "polar", "belts", "rings")
        val report = names.indices.joinToString("\n") { r ->
            val g = sums[r][0] / sums[r][2]; val l = sums[r][1] / sums[r][2]
            assertTrue("${names[r]} global=$g local=$l", l < g * .85)
            "${names[r]} globalMSE=$g localMSE=$l"
        }
        val directory = File("build/reports/stacking").apply { mkdirs() }
        File(directory, "planetary-region-metrics.txt").writeText(report + "\noptions=patch20,maxGlobal12,maxLocal5,maxPoints128\n")
        writePng(expected, File(directory, "planetary-ring-reference.png"))
        writePng(source, File(directory, "planetary-ring-distorted.png"))
        writePng(local, File(directory, "planetary-ring-local.png"))
        println(report)
    }

    @Test fun exactBestCountAndPercentageProduceLinearWeightedResults() {
        val original = frame()
        val frames = listOf(blur(original, 6), blur(original, 4), original, blur(original, 2))
        val directory = Files.createTempDirectory("planetary-test-").toFile()
        try {
            val source = Source(frames)
            val stacker = PlanetaryStacker()
            stacker.analyze(source, PlanetaryOptions(localAlignment = false), directory).use { analysis ->
                assertEquals(2L, analysis.referenceFrame)
                assertEquals(4L, analysis.validFrames)
                assertEquals(20L, analysis.indexFile.length())
                assertEquals(4L, analysis.histogram.sum())
                val best = stacker.stack(source, analysis, PlanetarySelection(percent = 10.0))
                assertEquals(1L, best.stats.selectedFrames)
                assertEquals(1L, best.stats.stackedFrames)
                assertArrayEquals(original.pixels, best.image.pixels, 0f)
                assertTrue(best.weights.all { it == 1f })
                val custom = stacker.stack(source, analysis, PlanetarySelection(count = 2))
                assertEquals(2L, custom.stats.stackedFrames)
                assertEquals(.02, custom.stats.effectiveExposureSeconds, 1e-9)
                assertEquals(0L, custom.stats.framesWithUnknownExposure)
            }
            assertTrue(directory.listFiles()!!.isEmpty())
            assertFalse("Source ownership belongs to caller", source.closed)
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidTranslatedEdgesDoNotDarkenTheAverage() {
        val directory = Files.createTempDirectory("planetary-edges-").toFile()
        try {
            val source = Source(listOf(frame(), frame(3.0, 0.0)), seconds = 0.0)
            val stacker = PlanetaryStacker()
            stacker.analyze(source, PlanetaryOptions(localAlignment = false, maxGlobalShift = 12), directory).use { analysis ->
                val result = stacker.stack(source, analysis, PlanetarySelection(percent = 100.0))
                assertEquals(2L, result.stats.stackedFrames)
                assertTrue(result.weights.any { it == 1f })
                assertTrue(result.weights.any { it == 2f })
                for (i in result.weights.indices) if (result.weights[i] == 1f) assertEquals(.012, result.image.pixels[i].toDouble(), .001)
                assertEquals(2L, result.stats.framesWithUnknownExposure)
                assertEquals(0.0, result.stats.effectiveExposureSeconds, 0.0)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationDeletesPartialAnalysisAndAllowsCallerToCloseSource() {
        val directory = Files.createTempDirectory("planetary-cancel-").toFile()
        try {
            val source = Source(List(5) { frame() })
            var checks = 0
            try {
                source.use { PlanetaryStacker().analyze(it, cacheDirectory = directory,
                    control = PlanetaryControl(checkCancelled = { if (++checks > 25) throw CancellationException("test") })) }
                fail("Cancellation expected")
            } catch (_: CancellationException) { }
            assertTrue(source.closed)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun memoryBudgetFailsBeforeReading() {
        val source = Source(listOf(frame()))
        try {
            PlanetaryStacker().analyze(source, PlanetaryOptions(memoryBudgetBytes = 1), File(System.getProperty("java.io.tmpdir")))
            fail("Budget violation expected")
        } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("smaller")) }
        assertEquals(0, source.reads)
    }

    @Test fun grosslyDifferentTargetIsRejectedByRegistration() {
        val random = Random(83)
        val wrong = frame().copy(pixels = FloatArray(width * height) { random.nextFloat() * .5f })
        val alignment = PlanetaryRegistration(frame(), roi, PlanetaryOptions()).align(wrong)
        assertEquals(PlanetaryRejection.GLOBAL_MATCH, alignment.rejection)
    }

    @Test fun insufficientAlignmentPointsExplicitlyDegradeToGlobal() {
        val alignment = PlanetaryRegistration(frame(), roi,
            PlanetaryOptions(alignmentPatchSize = 128, maxGlobalShift = 12)).align(frame(2.0, -1.0))
        assertEquals(PlanetaryRejection.NONE, alignment.rejection)
        assertEquals(PlanetaryRegistrationMode.GLOBAL_LOW_TEXTURE, alignment.mode)
        assertEquals(0, alignment.pointCount)
    }

    @Test fun doubledLimbIsRejectedInsteadOfStackingAGhost() {
        val directory = Files.createTempDirectory("planetary-ghost-").toFile()
        try {
            val reference = frame()
            val left = frame(-5.0); val right = frame(5.0)
            val ghost = reference.copy(pixels = FloatArray(width * height) { (left.pixels[it] + right.pixels[it]) * .5f })
            val source = Source(listOf(reference, ghost))
            val stacker = PlanetaryStacker()
            stacker.analyze(source, PlanetaryOptions(localAlignment = false), directory).use { analysis ->
                val result = stacker.stack(source, analysis, PlanetarySelection(percent = 100.0))
                assertEquals(result.stats.toString(), 1L, result.stats.stackedFrames)
                assertEquals(1L, result.stats.rejectionCounts[PlanetaryRejection.EDGE_MISMATCH])
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun stackAveragesIndependentNoiseAndCancelsDuringRegistration() {
        val directory = Files.createTempDirectory("planetary-noise-").toFile()
        try {
            val truth = frame()
            val random = Random(457)
            val source = Source(List(8) { truth.copy(pixels = FloatArray(width * height) { p -> (truth.pixels[p] + random.nextGaussian() * .006).toFloat() }) })
            val stacker = PlanetaryStacker()
            stacker.analyze(source, PlanetaryOptions(localAlignment = false), directory).use { analysis ->
                val result = stacker.stack(source, analysis, PlanetarySelection(percent = 100.0))
                assertEquals(8L, result.stats.stackedFrames)
                var square = 0.0; var n = 0
                for (y in 8 until 110) for (x in 8 until 18) { square += (result.image.pixels[y * width + x] - .012).pow(2); n++ }
                val rms = sqrt(square / n)
                assertTrue("Background RMS $rms must improve .006 input noise", rms < .003)
                var inRegistration = false
                try {
                    stacker.stack(source, analysis, PlanetarySelection(percent = 100.0), PlanetaryControl(
                        checkCancelled = { if (inRegistration) throw CancellationException("test") },
                        progress = { if (it.phase == PlanetaryPhase.REGISTER) inRegistration = true }))
                    fail("Cancellation expected")
                } catch (_: CancellationException) { }
                println("Planetary noise sigma .006 -> $rms over eight frames")
            }
        } finally { directory.deleteRecursively() }
    }

    private fun writePng(image: LinearImage, file: File) {
        DataOutputStream(file.outputStream()).use { out ->
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, bytes: ByteArray) {
                val name = type.toByteArray(Charsets.US_ASCII)
                val crc = CRC32().apply { update(name); update(bytes) }
                out.writeInt(bytes.size); out.write(name); out.write(bytes); out.writeInt(crc.value.toInt())
            }
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { it.writeInt(image.width); it.writeInt(image.height); it.write(byteArrayOf(8, 0, 0, 0, 0)) }
            chunk("IHDR", header.toByteArray())
            val compressed = ByteArrayOutputStream()
            DeflaterOutputStream(compressed).use { data ->
                for (y in 0 until image.height) {
                    data.write(0)
                    for (x in 0 until image.width) data.write((image.pixels[y * image.width + x].coerceIn(0f, 1f) * 255).roundToInt())
                }
            }
            chunk("IDAT", compressed.toByteArray())
            chunk("IEND", byteArrayOf())
        }
    }
}
