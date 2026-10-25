package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.math.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

/** Uses untouched sensor samples from a recorded IMX585 field, optionally the original full FITS. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideFitsReplayTest {
    private val module = GuideModule()

    @Test fun portableRecordedPixelsMatchTheirProvenanceHash() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/guide/imx585-field.raw8.gz"))
        val pixels = GZIPInputStream(resource).use { it.readBytes() }
        val metadata = requireNotNull(javaClass.getResourceAsStream("/guide/imx585-field.json")).bufferedReader().use { it.readText() }
        val expected = metadata.substringAfter("\"rawSha256\": \"").substringBefore('"')
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(pixels).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(1024 * 768, pixels.size)
        assertEquals(expected, actual)
    }

    @Test fun realFieldDetectionAndProfile() {
        val frame = field.frame()
        val stars = module.usableStars(module.detectStars(frame))
        report("stars", "x,y,snr,flux,radius,hfr,peak,background", stars.map { star ->
            val profile = requireNotNull(GuideStarAnalysis.measure(frame, star))
            "${star.x},${star.y},${star.snr},${star.flux},${star.radiusPx},${profile.hfrPx},${profile.peak},${profile.background}"
        })
        assertTrue("At least four usable stars are needed in this recorded field", stars.size >= 4)
        val primary = requireNotNull(GuideStarAnalysis.measure(frame, stars.first()))
        report("profile", "radius,intensity,energy", primary.radialIntensity.indices.map {
            "$it,${primary.radialIntensity[it]},${primary.encircledEnergy[it]}"
        })
    }

    @Test fun usableStarsExcludeClippedImageEdges() {
        val frame = field.frame()
        assertTrue(stars(frame).all { it.x >= 12f && it.y >= 12f && it.x < frame.width - 12f && it.y < frame.height - 12f })
    }

    @Test fun usableStarsExcludeSaturationInsideTheWholeCentroidAperture() {
        val frame = field.frame()
        stars(frame).forEach { star ->
            assertTrue("Saturated aperture at ${star.x},${star.y}", requireNotNull(GuideStarAnalysis.measure(frame, star)).peak < 254f)
        }
    }

    @Test fun aStarlessBayerBackgroundCannotBecomeGuideStars() {
        val random = java.util.Random(585)
        val levels = intArrayOf(24, 30, 30, 19)
        val pixels = ByteArray(128 * 128) { i ->
            (levels[(i / 128 % 2) * 2 + i % 2] + random.nextInt(5) - 2).toByte()
        }
        assertTrue(stars(FrameData(pixels, 128, 128, PixelFormat.BAYER_RG8, 1, 0)).isEmpty())
    }

    @Test fun phaseBackgroundOffsetsCannotChangeHfrOrFlux() {
        fun frame(levels: IntArray) = FrameData(ByteArray(64 * 64) { i ->
            val x = i % 64; val y = i / 64
            val signal = (60 * exp(-((x - 32.0).pow(2) + (y - 32.0).pow(2)) / 8)).toInt()
            (signal + levels[(y % 2) * 2 + x % 2]).toByte()
        }, 64, 64, PixelFormat.BAYER_RG8, 1, 0)
        val star = GuideStar(32f, 32f, 20f)
        val reference = requireNotNull(GuideStarAnalysis.measure(frame(intArrayOf(10, 10, 10, 10)), star))
        val colored = requireNotNull(GuideStarAnalysis.measure(frame(intArrayOf(24, 30, 30, 19)), star))
        assertEquals(reference.hfrPx, colored.hfrPx, .02f)
        assertEquals(reference.flux, colored.flux, 1f)
    }

    @Test fun hysteresisClosesTheLoopOnRecordedImages() = runTest(timeout = 180.seconds) { replayClosedLoop(GuideAlgorithm.HYSTERESIS) }
    @Test fun lowPassClosesTheLoopOnRecordedImages() = runTest(timeout = 180.seconds) { replayClosedLoop(GuideAlgorithm.LOW_PASS) }
    @Test fun resistSwitchClosesTheLoopOnRecordedImages() = runTest(timeout = 180.seconds) { replayClosedLoop(GuideAlgorithm.RESIST_SWITCH) }
    @Test fun predictiveRaClosesTheLoopOnRecordedImages() = runTest(timeout = 180.seconds) { replayClosedLoop(GuideAlgorithm.PREDICTIVE_RA) }

    private suspend fun TestScope.replayClosedLoop(algorithm: GuideAlgorithm) {
        val plant = ImageMount()
        val calibrated = plant.calibrate()
        val lockedX = plant.x; val lockedY = plant.y
        val controller = GuideModule()
        val backlash = GuideBacklashCompensation().apply { accepted(MountDirection.SOUTH) }
        val runner = GuideTaskRunner(this, { direction, ms -> plant.pulse(direction, ms) }, {})
        plant.x += 2.5f; plant.y -= 1.75f
        var openX = 2.5f; var openY = -1.75f
        val errors = mutableListOf<Float>(); val openErrors = mutableListOf<Float>(); val rows = mutableListOf<String>()
        repeat(72) { index ->
            val driftX = .03f + .10f * (sin(index * .2f) - sin((index - 1) * .2f))
            val driftY = .015f + .08f * (sin(index * .3f) - sin((index - 1) * .3f))
            plant.x += driftX; plant.y += driftY; openX += driftX; openY += driftY
            val actualX = plant.x - lockedX; val actualY = plant.y - lockedY
            val measured = requireNotNull(controller.matchShift(calibrated.stars, stars(field.frame(plant.x, plant.y))))
            val solution = requireNotNull(controller.solveCorrection(calibrated.calibration, measured.x, measured.y,
                algorithm, .7f, .7f, .15f, backlash))
            val correction = solution.correction
            var sentRa = 0; var sentDec = 0
            assertTrue(runner.launch {
                correction.raDirection?.let { direction -> pulse(direction, correction.raPulseMs) {
                    sentRa = if (direction == MountDirection.EAST) correction.raPulseMs else -correction.raPulseMs
                } }
                correction.decDirection?.let { direction -> pulse(direction, correction.decPulseMs) {
                    backlash.accepted(direction)
                    sentDec = if (direction == MountDirection.NORTH) correction.decPulseMs else -correction.decPulseMs
                } }
            })
            advanceUntilIdle()
            val actual = hypot(actualX, actualY); val open = hypot(openX, openY)
            errors += actual; openErrors += open
            rows += "$index,$actual,${measured.length},${hypot(measured.x - actualX, measured.y - actualY)},$open,$sentRa,$sentDec,${solution.raErrorPx},${solution.decErrorPx}"
        }
        report("closed-loop-${algorithm.name}", "cycle,trueErrorPx,measuredErrorPx,measurementErrorPx,openLoopErrorPx,raPulseMs,decPulseMs,raErrorPx,decErrorPx", rows)
        fun rms(values: List<Float>) = sqrt(values.sumOf { it.toDouble() * it } / values.size)
        val settled = rms(errors.takeLast(30)); val uncontrolled = rms(openErrors.takeLast(30))
        assertTrue("$algorithm residual RMS $settled px", settled < .5)
        assertTrue("$algorithm must reduce the specified drift", settled < uncontrolled * .2)
    }

    @Test fun recordedImageLossAndCancellationStopQueuedCorrections() = runTest {
        val references = stars(field.frame())
        val blank = FrameData(ByteArray(field.width * field.height), field.width, field.height, PixelFormat.BAYER_RG8, 2, 0)
        assertNull(module.matchShift(references, stars(blank)))
        val actions = mutableListOf<String>()
        val runner = GuideTaskRunner(this, { d, _ -> actions += "start-$d" }, { d -> actions += "stop-$d" })
        runner.launch { pulse(MountDirection.EAST, 1000); pulse(MountDirection.NORTH, 1000) }
        runCurrent(); advanceTimeBy(100); runner.cancel(); advanceUntilIdle()
        assertEquals(listOf("start-EAST", "stop-EAST"), actions)
    }

    @Test fun integerAndSubpixelTranslationsRecoverTheKnownMotion() {
        val reference = stars(field.frame())
        val offsets = listOf(2f to -4f, -12f to 8f, 20f to 16f, .25f to .5f, .5f to -.75f,
            .75f to 1.25f, 1f to 1f, -1.75f to 2.25f, 3.25f to -2.5f)
        val rows = offsets.map { (x, y) ->
            val shift = requireNotNull(module.matchShift(reference, stars(field.frame(x, y)))) { "Lost shift $x,$y" }
            val error = hypot(shift.x - x, shift.y - y)
            "$x,$y,${shift.x},${shift.y},$error"
        }
        report("translations", "expectedX,expectedY,measuredX,measuredY,errorPx", rows)
        rows.forEach { row -> assertTrue("Translation $row", row.substringAfterLast(',').toFloat() <= .2f) }
    }

    @Test fun realImageCalibrationMeasuresRotatedAxesAndBacklash() = runTest(timeout = 120.seconds) {
        val plant = ImageMount()
        val result = plant.calibrate()
        report("calibration", "eastX,eastY,northX,northY,raBacklash,decBacklash,eastSteps,northSteps,residual", listOf(
            result.calibration.let { "${it.eastXPerMs},${it.eastYPerMs},${it.northXPerMs},${it.northYPerMs},${it.raBacklashMs},${it.decBacklashMs},${it.eastSteps},${it.northSteps},${it.fitResidualPx}" }))
        val c = result.calibration
        assertEquals(ex, c.eastXPerMs, .0002f)
        assertEquals(ey, c.eastYPerMs, .0002f)
        assertEquals(nx, c.northXPerMs, .0003f)
        assertEquals(ny, c.northYPerMs, .0003f)
        assertEquals(1800f, c.decBacklashMs.toFloat(), 250f)
        assertTrue(c.eastSteps >= 7 && c.northSteps >= 7)
        assertTrue(c.eastTravelPx >= 15 && c.northTravelPx >= 15)
    }

    private fun stars(frame: FrameData) = module.usableStars(module.detectStars(frame))

    private inner class ImageMount {
        var x = 0f; var y = 0f
        private var raDirection: MountDirection? = null
        private var decDirection: MountDirection? = null
        private var raSlack = 0; private var decSlack = 0
        fun pulse(direction: MountDirection, ms: Int) {
            val ra = direction == MountDirection.EAST || direction == MountDirection.WEST
            val previous = if (ra) raDirection else decDirection
            if (previous != direction) {
                if (ra) raSlack = 600 else decSlack = 1800
            }
            val slack = if (ra) raSlack else decSlack
            val moving = (ms - slack).coerceAtLeast(0)
            val sign = if (direction == MountDirection.EAST || direction == MountDirection.NORTH) 1 else -1
            x += moving * sign * if (ra) ex else nx
            y += moving * sign * if (ra) ey else ny
            if (ra) { raSlack = (slack - ms).coerceAtLeast(0); raDirection = direction }
            else { decSlack = (slack - ms).coerceAtLeast(0); decDirection = direction }
        }
        suspend fun calibrate() = GuideCalibrator(GuideCalibrationConfig(), { d, ms -> pulse(d, ms) }, {
            stars(field.frame(x, y)).take(12)
        }).run()
    }

    private fun report(name: String, header: String, rows: List<String>) {
        val root = File(System.getenv("GUIDE_FITS_REPORT_DIR") ?: "build/reports/guide-fits").apply { mkdirs() }
        File(root, "$name.csv").writeText(header + "\n" + rows.joinToString("\n") + "\n")
    }

    private class RecordedField(val pixels: ByteArray, val width: Int, val height: Int) {
        init { require(pixels.size.toLong() == width.toLong() * height) }
        private val backgrounds = IntArray(4) { phase ->
            val samples = mutableListOf<Int>()
            for (y in (phase / 2) until height step 16) for (x in (phase % 2) until width step 16) samples += pixels[y * width + x].toInt() and 255
            samples.sorted()[samples.size / 2]
        }
        /** Translate each CFA phase on its own sampling grid; the sensor's color lattice stays fixed. */
        fun frame(dx: Float = 0f, dy: Float = 0f): FrameData {
            if (dx == 0f && dy == 0f) return FrameData(pixels, width, height, PixelFormat.BAYER_RG8, 1, 0)
            val xShift = dx / 2; val yShift = dy / 2
            val ix = floor(xShift).toInt(); val iy = floor(yShift).toInt()
            val fx = xShift - ix; val fy = yShift - iy
            val output = ByteArray(pixels.size)
            for (y in 0 until height) for (x in 0 until width) {
                val sx = x - ix * 2; val sy = y - iy * 2
                val background = backgrounds[(y % 2) * 2 + x % 2]
                fun sample(px: Int, py: Int): Int = if (px in 0 until width && py in 0 until height) pixels[py * width + px].toInt() and 255 else background
                val a = sample(sx, sy) * (1 - fx) + sample(sx - 2, sy) * fx
                val b = sample(sx, sy - 2) * (1 - fx) + sample(sx - 2, sy - 2) * fx
                output[y * width + x] = (a * (1 - fy) + b * fy).roundToInt().coerceIn(0, 255).toByte()
            }
            return FrameData(output, width, height, PixelFormat.BAYER_RG8, 1, 0)
        }
    }

    companion object {
        private val field by lazy {
            val external = System.getenv("GUIDE_FITS_PATH")
            if (external != null) RandomAccessFile(external, "r").use { file ->
                val header = FitsHeaderParser.read(file)
                require(header.intValue("BITPIX") == 8 && header.intValue("NAXIS") == 2)
                require((header.doubleValue("BSCALE") ?: 1.0) == 1.0 && (header.doubleValue("BZERO") ?: 0.0) == 0.0)
                val w = requireNotNull(header.intValue("NAXIS1")); val h = requireNotNull(header.intValue("NAXIS2"))
                val data = ByteArray(w * h); file.readFully(data); RecordedField(data, w, h)
            } else {
                val stream = requireNotNull(GuideFitsReplayTest::class.java.getResourceAsStream("/guide/imx585-field.raw8.gz"))
                RecordedField(GZIPInputStream(stream).use { it.readBytes() }, 1024, 768)
            }
        }
        private const val ex = .0034641016f
        private const val ey = .002f
        private const val nx = -.003f
        private const val ny = .0051961524f
    }
}
