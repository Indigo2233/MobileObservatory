package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import kotlin.math.exp
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class GuideLocalReconstructionTest {
    @Test fun rawPrefilterRetainsEveryAboveThresholdWindowAcrossPhaseAndAduBoundaries() {
        val random = Random(585)
        for (bits in listOf(8, 14, 16)) {
            val maximum = (1 shl bits) - 1
            val pixels = IntArray(65 * 67) { random.nextInt(maximum + 1) }
            val frame = frame(pixels, 65, 67, PixelFormat.valueOf("BAYER_RG$bits"))
            val image = GuideImage(frame)
            for (phaseMeans in listOf(doubleArrayOf(0.0, 0.0, 0.0, 0.0),
                doubleArrayOf(.91 * maximum, .04 * maximum, .03 * maximum, .02 * maximum))) {
                for (threshold in listOf(0.0, maximum / 2.0, maximum - 1.0, maximum - .5)) {
                    val mask = GuideCandidateMask(frame, phaseMeans, threshold)
                    for (y in 0 until frame.height - 1) {
                        mask.prepareRow(y)
                        for (x in 0 until frame.width - 1) if (image.value(x, y) > threshold)
                            assertTrue("RAW$bits ($x,$y), threshold=$threshold", mask.mayContainSignal(x))
                    }
                }
            }
        }
    }

    @Test fun unequalCfaSkyIsSkippedWhileOffGridSignalSurvives() {
        val background = intArrayOf(200, 320, 320, 100)
        val pixels = IntArray(96 * 64) { index ->
            val x = index % 96; val y = index / 96
            background[(y and 1) * 2 + (x and 1)] +
                (400 * exp(-((x - 47.3) * (x - 47.3) + (y - 31.8) * (y - 31.8)) / 8)).toInt()
        }
        val frame = frame(pixels, 96, 64, PixelFormat.BAYER_RG14)
        val image = GuideImage(frame)
        val mask = GuideCandidateMask(frame, background.map { it.toDouble() }.toDoubleArray(), 250.0)
        var eligible = 0
        for (y in 0 until 63) {
            mask.prepareRow(y)
            for (x in 0 until 95) {
                if (mask.mayContainSignal(x)) eligible++
                if (image.value(x, y) > 250) assertTrue(mask.mayContainSignal(x))
                if (x < 20 || x > 75) assertFalse(mask.mayContainSignal(x))
            }
        }
        assertTrue(eligible in 1 until pixels.size / 10)
    }

    @Test fun flatCfaBackgroundAtTheDiscoveryThresholdNeedsNoReconstruction() {
        val background = intArrayOf(200, 320, 320, 100)
        val pixels = IntArray(64 * 64) { background[(it / 64 and 1) * 2 + (it and 1)] }
        val frame = frame(pixels, 64, 64, PixelFormat.BAYER_RG14)
        val mask = GuideCandidateMask(frame, background.map { it.toDouble() }.toDoubleArray(), 235.0)
        for (y in 0 until 63) {
            mask.prepareRow(y)
            for (x in 0 until 63) assertFalse(mask.mayContainSignal(x))
        }
    }

    @Test fun localDiscoveryMatchesEagerFullReconstructionOnColoredAndWeakStars() {
        val random = Random(982)
        val width = 193; val height = 161 // Includes the partial final background sample.
        val module = GuideModule()
        for (sigma in listOf(.8, 1.5, 2.5)) {
            val background = intArrayOf(200, 320, 320, 100)
            val response = doubleArrayOf(4.0, .4, .4, .08)
            val pixels = IntArray(width * height) { index ->
                val x = index % width; val y = index / width
                val phase = (y and 1) * 2 + (x and 1)
                var value = background[phase] + random.nextInt(7) + x / 19.0
                for (star in 0..5) {
                    val cx = 30.2 + star % 3 * 59.0; val cy = 37.4 + star / 3 * 77.0
                    value += (120 + star * 150) * response[phase] *
                        exp(-((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (2 * sigma * sigma))
                }
                value.toInt()
            }
            val raw = frame(pixels, width, height, PixelFormat.BAYER_RG14)
            val image = GuideImage(raw)
            val eagerPixels = IntArray(pixels.size) { image.value(it % width, it / width) }
            val eager = frame(eagerPixels, width, height, PixelFormat.MONO14)
            val expected = module.usableStars(module.detectStars(eager))
            val actual = module.usableStars(module.detectStars(raw))
            assertTrue(expected.isNotEmpty())
            assertEquals(expected.size, actual.size)
            expected.zip(actual).forEach { (a, b) ->
                assertEquals(a.x + .5f, b.x, 1e-5f)
                assertEquals(a.y + .5f, b.y, 1e-5f)
                assertEquals(a.flux, b.flux, 0f)
                assertEquals(a.snr, b.snr, 0f)
                assertEquals(a.hfdPx, b.hfdPx, 0f)
                assertEquals(a.radiusPx, b.radiusPx, 0f)
                assertEquals(a.saturated, b.saturated)
            }
        }
    }

    @Test fun starPatchMatchesCppReferenceRegardlessOfSensorWidthAndCacheReuse() {
        val fixture = Phd2StarFixture.load().first { it.name == "color-True-1.8-4" }
        val width = 4096; val height = 256; val originX = 2048; val originY = 100
        val pixels = IntArray(width * height) { 100 }
        for (y in 0 until fixture.height) for (x in 0 until fixture.width)
            pixels[(y + originY) * width + x + originX] = fixture.input[y * fixture.width + x]
        val frame = frame(pixels, width, height, PixelFormat.BAYER_RG14)
        val image = GuideImage(frame)
        assertEquals(0, image.cachedPixels)
        repeat(2) {
            val measured = Phd2StarMeasurement.find(image, fixture.baseX + originX, fixture.baseY + originY,
                fixture.search, fixture.maximum)
            assertEquals(fixture.x + originX, measured.x, 1e-10)
            assertEquals(fixture.y + originY, measured.y, 1e-10)
            assertEquals(fixture.mass, measured.mass, 1e-8)
            assertEquals(fixture.snr, measured.snr, 1e-10)
            assertEquals(fixture.hfd, measured.hfd, 1e-10)
            assertTrue(image.cachedPixels <= 35 * 35) // The upstream fixture uses a 5 px search.
            Phd2StarMeasurement.find(image, 40, 40)
        }
    }

    private fun frame(pixels: IntArray, width: Int, height: Int, format: PixelFormat): FrameData {
        val stride = format.bytesPerPixel
        val bytes = ByteArray(pixels.size * stride)
        pixels.forEachIndexed { index, value ->
            bytes[index * stride] = value.toByte()
            if (stride == 2) bytes[index * stride + 1] = (value ushr 8).toByte()
        }
        return FrameData(bytes, width, height, format, 1, 0)
    }
}
