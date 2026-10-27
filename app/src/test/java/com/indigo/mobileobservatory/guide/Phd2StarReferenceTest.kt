package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.CameraCfaLayout
import com.indigo.mobileobservatory.stacking.PixelTraits
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

internal data class Phd2StarFixture(val fields: List<String>) {
    val name = fields[0]
    val width = fields[1].toInt(); val height = fields[2].toInt()
    val recon = fields[3] == "1"
    val baseX = fields[4].toInt(); val baseY = fields[5].toInt(); val search = fields[6].toInt()
    val bits = fields[7].toInt(); val maximum = fields[8].toInt()
    val input = fields[9].chunked(4).map { it.toInt(16) }
    val luminance = fields[10].chunked(4).map { it.toInt(16) }
    val x = fields[11].toDouble(); val y = fields[12].toDouble()
    val mass = fields[13].number(); val snr = fields[14].number(); val hfd = fields[15].number()
    val peak = fields[16].toInt(); val result = fields[17].toInt()
    fun frame(): FrameData {
        val bpp = if (bits == 8) 1 else 2
        val bytes = ByteArray(input.size * bpp)
        input.forEachIndexed { i, value -> bytes[i * bpp] = value.toByte(); if (bpp == 2) bytes[i * bpp + 1] = (value ushr 8).toByte() }
        val format = PixelFormat.valueOf((if (recon) "BAYER_RG" else "MONO") + bits)
        return FrameData(bytes, width, height, format, 1, 0)
    }
    companion object {
        private fun String.number(): Double = when (lowercase()) {
            "nan", "-nan" -> Double.NaN
            "inf", "+inf" -> Double.POSITIVE_INFINITY
            "-inf" -> Double.NEGATIVE_INFINITY
            else -> toDouble()
        }
        fun load(): List<Phd2StarFixture> = GZIPInputStream(requireNotNull(
            Phd2StarFixture::class.java.getResourceAsStream("/guide/phd2-star-reference.csv.gz")))
            .bufferedReader().useLines { lines -> lines.drop(1).filter { it.isNotEmpty() }.map { Phd2StarFixture(it.split(',')) }.toList() }
    }
}

class Phd2StarReferenceTest {
    @Test fun reconstructionAndStarFindMatchAllOriginalCppReferenceSamples() {
        val fixtures = Phd2StarFixture.load()
        assertEquals(82, fixtures.size)
        for (f in fixtures) {
            val image = GuideImage(f.frame())
            for (y in 0 until f.height) for (x in 0 until f.width)
                assertEquals("${f.name} pixel($x,$y)", f.luminance[y * f.width + x], image.value(x, y))
            if (f.width < 25 || f.height < 25) continue
            val measurement = Phd2StarMeasurement.find(image, f.baseX, f.baseY, f.search, f.maximum)
            assertEquals(f.name, f.result, measurement.result.ordinal)
            assertEquals("${f.name} X", f.x, measurement.x, 1e-10)
            assertEquals("${f.name} Y", f.y, measurement.y, 1e-10)
            assertEquals("${f.name} mass", f.mass, measurement.mass, 1e-8)
            assertEquals("${f.name} SNR", f.snr, measurement.snr, 1e-10)
            assertEquals("${f.name} HFD", f.hfd, measurement.hfd, 1e-10)
            assertEquals("${f.name} peak", f.peak, measurement.peak)
        }
    }

    @Test fun sparseReadsAvoidRowAllocationAndLocalPhotometryKeepsTheRawBytesUnchanged() {
        val pixels = ByteArray(256 * 512) { ((it * 17) % 251).toByte() }
        val original = pixels.clone()
        val image = GuideImage(FrameData(pixels, 256, 512, PixelFormat.BAYER_RG8, 1, 0))
        for (y in 0 until image.height) for (x in 0 until image.width) image.value(x, y)
        assertEquals(0, image.cachedPixels)
        assertArrayEquals(original, pixels)
        assertEquals((pixels[0].toInt().and(255) + pixels[1].toInt().and(255) +
            pixels[256].toInt().and(255) + pixels[257].toInt().and(255)) / 4, image.value(0, 0))
        Phd2StarMeasurement.find(image, 128, 256)
        assertTrue(image.cachedPixels <= 31 * 31)
        assertArrayEquals(original, pixels)
    }

    @Test fun rawClippingInOneColorChannelSurvivesLuminanceAveraging() {
        val source = Phd2StarFixture.load().first { it.name == "color-True-1.8-4" }.frame()
        val bytes = source.data.clone()
        val x = 32; val y = 32; val index = (y * source.width + x) * 2
        bytes[index] = 255.toByte(); bytes[index + 1] = 63 // RAW14 clips one CFA phase
        val frame = source.copy(data = bytes)
        val image = GuideImage(frame)
        assertTrue(image.value(x, y) < 16383)
        val stars = GuideModule().detectStars(frame, includeRejected = true)
        assertTrue(stars.isNotEmpty())
        assertTrue(stars.all { it.saturated })
        assertTrue(GuideModule().usableStars(stars).isEmpty())
    }

    @Test fun smoothingAnUnsaturatedHotPixelDoesNotTurnItIntoAnEligibleStar() {
        val pixels = ByteArray(64 * 64) { 10 }.apply { this[32 * 64 + 32] = 190.toByte() }
        val frame = FrameData(pixels, 64, 64, PixelFormat.BAYER_RG8, 1, 0)
        val detected = GuideModule().detectStars(frame, includeRejected = true)
        assertTrue(detected.isNotEmpty())
        assertTrue(detected.all { !it.saturated && it.hfdPx < 1.5f })
        assertTrue(GuideModule().usableStars(detected).isEmpty())
    }

    @Test fun selectedFaintStarCanBeMeasuredBelowTheGlobalCandidateThreshold() {
        val pixels = ByteArray(96 * 64) { index ->
            val x = index % 96; val y = index / 96
            (10 + 400 * kotlin.math.exp(-((x - 30.2) * (x - 30.2) + (y - 32.1) * (y - 32.1)) / 8) +
                60 * kotlin.math.exp(-((x - 50.3) * (x - 50.3) + (y - 32.1) * (y - 32.1)) / 8)).toInt().coerceAtMost(255).toByte()
        }
        val frame = FrameData(pixels, 96, 64, PixelFormat.BAYER_RG8, 1, 0)
        val selected = GuideStar(50.3f, 32.1f, 10f, 1000f)
        val usable = GuideModule().usableStars(GuideModule().detectStars(frame, preferred = selected, includeRejected = true))
        assertTrue(usable.any { hypot(it.x - 50.3, it.y - 32.1) < .2 })
    }

    @Test fun knownSdkBinnedMonochromeOutputIsNotReconstructedAgain() {
        val frame = Phd2StarFixture.load().first { it.name == "color-False-1.8-4" }.frame()
        val scalar = CameraCfaLayout.displayFrame(frame, PixelTraits(binning = 2, bayerPatternAtOutput = false))
        assertEquals(PixelFormat.MONO14, scalar.pixelFormat)
        assertEquals(0.0, GuideImage(scalar).sensorOffset, 0.0)
        assertEquals(GuideModule().detectStars(frame.copy(pixelFormat = PixelFormat.MONO14)), GuideModule().detectStars(scalar))
        val preserved = CameraCfaLayout.displayFrame(frame, PixelTraits(binning = 2, bayerPatternAtOutput = true))
        assertEquals(frame.pixelFormat, preserved.pixelFormat)
        assertEquals(.5, GuideImage(preserved).sensorOffset, 0.0)
    }

    @Test fun automaticSelectionPrefersNonClippingStarsWhileTrackingAllowsLowerSnr() {
        val module = GuideModule()
        val nearClip = GuideStar(20f, 20f, 30f, 400f, 2f, peakFraction = .95f)
        val healthy = nearClip.copy(x = 50f, flux = 200f, peakFraction = .7f)
        val weak = healthy.copy(snr = 4f)
        assertEquals(healthy, module.autoSelectStar(listOf(nearClip, healthy)))
        assertEquals(listOf(weak), module.usableStars(listOf(weak)))
        assertNull(module.autoSelectStar(listOf(weak)))
    }

    @Test fun coloredStarCentroidsAndPhotometryMatchTheCompiledUpstreamChain() {
        val module = GuideModule()
        val report = mutableListOf("name,actualX,actualY,referenceX,referenceY,actualErrorPx,referenceErrorPx")
        val groups = linkedMapOf<String, Pair<MutableList<Double>, MutableList<Double>>>()
        var largestDifference = 0.0
        var largestPhotometryDifference = 0.0
        for (f in Phd2StarFixture.load().filter { it.name.startsWith("color-") }) {
            val step = f.name.substringAfterLast('-').toInt()
            val truthX = 30.1 + step * .23; val truthY = 31.17 + step * .19
            val star = requireNotNull(module.detectStars(f.frame()).minByOrNull { hypot(it.x - truthX, it.y - truthY) }) { f.name }
            // QuickLRecon samples a window centred at (+0.5,+0.5); public coordinates remain sensor pixels.
            val refX = f.x + .5; val refY = f.y + .5
            val actualError = hypot(star.x - truthX, star.y - truthY)
            val referenceError = hypot(refX - truthX, refY - truthY)
            largestDifference = maxOf(largestDifference, hypot(star.x - refX, star.y - refY))
            largestPhotometryDifference = maxOf(largestPhotometryDifference, kotlin.math.abs(star.snr - f.snr))
            val group = groups.getOrPut(f.name.substringBeforeLast('-')) { mutableListOf<Double>() to mutableListOf() }
            group.first += actualError; group.second += referenceError
            report += "${f.name},${star.x},${star.y},$refX,$refY,$actualError,$referenceError"
        }
        val dir = File("build/reports/guide-color").apply { mkdirs() }
        File(dir, "phase-sweep.csv").writeText(report.joinToString("\n") + "\n")
        File(dir, "phase-sweep-rms.csv").writeText("case,actualRmsPx,referenceRmsPx\n" + groups.entries.joinToString("\n") {
            fun rms(values: List<Double>) = sqrt(values.sumOf { v -> v * v } / values.size)
            "${it.key},${rms(it.value.first)},${rms(it.value.second)}"
        } + "\n")
        assertEquals("Maximum centroid difference", 0.0, largestDifference, 1e-5)
        assertEquals("Maximum SNR difference", 0.0, largestPhotometryDifference, 1e-4)
    }
}
