package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.PixelFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

class CalibrationTest {
    private val geometry = CalibrationGeometry(4, 2, "MONO", 12)
    private val capture = CalibrationCapture("camera", 1_000_000.0, 100.0, 12.0, 0, 0, 1, "L", "RAW", -10.0)
    private fun image(values: FloatArray, g: CalibrationGeometry = geometry) = LinearImage(g.width, g.height, g.channels, values)
    private fun master(type: CalibrationType, values: FloatArray, g: CalibrationGeometry = geometry,
                       c: CalibrationCapture = capture, bias: CalibrationMaster? = null): CalibrationMaster =
        CalibrationBuilder.build(type, g, c, 1, { image(values.copyOf(), g) }, bias = bias)
    private fun directMaster(type: CalibrationType, values: FloatArray, g: CalibrationGeometry = geometry): CalibrationMaster {
        val descriptor = CalibrationDescriptor(type.name, "", type, g, capture, "", 1, 1)
        val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.BIG_ENDIAN)
        buffer.asFloatBuffer().put(values)
        return CalibrationMaster.direct(descriptor, buffer)
    }
    private fun heapMaster(type: CalibrationType, values: FloatArray, g: CalibrationGeometry = geometry): CalibrationMaster {
        val descriptor = CalibrationDescriptor(type.name, "", type, g, capture, "", 1, 1)
        return CalibrationMaster(descriptor, values.copyOf())
    }
    private fun crop(source: LinearImage, region: PixelRoi): LinearImage {
        val values = FloatArray(region.width * region.height * source.channels)
        for (y in 0 until region.height) for (x in 0 until region.width) for (c in 0 until source.channels) {
            values[(y * region.width + x) * source.channels + c] =
                source.pixels[((region.top + y) * source.width + region.left + x) * source.channels + c]
        }
        return LinearImage(region.width, region.height, source.channels, values)
    }
    private fun rejects(part: String, action: () -> Unit) {
        try { action(); fail("Expected $part") } catch (e: IllegalArgumentException) { assertTrue(e.message, e.message.orEmpty().contains(part)) }
    }

    @Test fun allEightOptionalSubsetsUseDarkOrBiasExactlyOnceAndPreserveNegativeLights() {
        val bias = master(CalibrationType.BIAS, FloatArray(8) { .1f })
        val dark = master(CalibrationType.DARK, FloatArray(8) { .12f })
        val response = floatArrayOf(.5f, 1.5f, .75f, 1.25f, .5f, 1.5f, .75f, 1.25f)
        val flat = master(CalibrationType.FLAT, FloatArray(8) { .1f + .4f * response[it] }, bias = bias)
        val light = image(floatArrayOf(.02f, .2f, .3f, .4f, .5f, .6f, .7f, .8f))
        val original = light.pixels.copyOf()
        for (mask in 0..7) {
            val selected = CalibrationSelection(bias.takeIf { mask and 1 != 0 }, dark.takeIf { mask and 2 != 0 }, flat.takeIf { mask and 4 != 0 })
            val result = selected.apply(light, geometry, capture)
            for (i in original.indices) {
                val subtract = if (mask and 2 != 0) .12f else if (mask and 1 != 0) .1f else 0f
                val divide = if (mask and 4 != 0) response[i] else 1f
                assertEquals("mask=$mask pixel=$i", (original[i] - subtract) / divide, result.pixels[i], 3e-7f)
            }
            if (mask == 0) assertSame(light, result) else assertNotSame(light, result)
        }
        assertArrayEquals(original, light.pixels, 0f)
        val alteredCopy = dark.imageCopy(); alteredCopy.pixels.fill(0f)
        assertEquals(.12f, dark.imageCopy().pixels[0], 0f)
        assertTrue(CalibrationSelection(bias, dark, flat).apply(light, geometry, capture).pixels[0] < 0f)
        assertEquals(bias.descriptor.id, flat.descriptor.biasId)
    }

    @Test fun flatNormalizesEachCfaParitySeparatelyWithoutNeutralizingColor() {
        for (layout in listOf("RGGB", "GRBG", "GBRG", "BGGR")) {
            val g = CalibrationGeometry(4, 4, layout, 16)
            val levels = floatArrayOf(.2f, .3f, .4f, .5f)
            val response = FloatArray(16) { if (it % 4 < 2) .5f else 1.5f }
            val bias = master(CalibrationType.BIAS, FloatArray(16) { .07f }, g)
            val flat = master(CalibrationType.FLAT, FloatArray(16) { .07f + levels[g.group(it)] * response[it] }, g, bias = bias)
            assertArrayEquals(response, flat.imageCopy().pixels, 4e-7f)
            val lights = image(FloatArray(16) { .07f + levels[g.group(it)] * response[it] }, g)
            val calibrated = CalibrationSelection(bias = bias, flat = flat).apply(lights, g, capture)
            for (i in 0 until 16) assertEquals(levels[g.group(i)], calibrated.pixels[i], 3e-7f)
        }
    }

    @Test fun rgbFlatNormalizesChannelsIndependently() {
        val g = CalibrationGeometry(2, 1, "RGB", 16)
        val flat = master(CalibrationType.FLAT, floatArrayOf(.1f, .2f, .3f, .3f, .6f, .9f), g)
        assertArrayEquals(floatArrayOf(.5f, .5f, .5f, 1.5f, 1.5f, 1.5f), flat.imageCopy().pixels, 2e-7f)
    }

    @Test fun rejectsClippedNonfiniteAndNonpositiveFlatResponse() {
        for (bad in listOf(0f, 1f)) {
            val g = CalibrationGeometry(16, 16, "MONO", 16)
            val flat = master(CalibrationType.FLAT, FloatArray(256) { if (it == 3) bad else .5f }, g)
            assertEquals(1f, flat.imageCopy().pixels[3], 1e-4f)
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.01f)) rejects("invalid_source") {
            master(CalibrationType.FLAT, FloatArray(8) { if (it == 3) bad else .5f })
        }
        rejects("clipped_flat") {
            master(CalibrationType.FLAT, FloatArray(8) { if (it == 3) 0f else .5f })
        }
        val bias = master(CalibrationType.BIAS, FloatArray(8) { .6f })
        for (response in listOf(.5f, .6f)) rejects("invalid_flat_response") {
            master(CalibrationType.FLAT, FloatArray(8) { response }, bias = bias)
        }
        rejects("uncalibrated sensor means") { master(CalibrationType.DARK, FloatArray(8) { .7f }, bias = bias) }
    }

    @Test fun sparseFlatDefectsAreRepairedFromTheSameCfaGroupWithoutChangingImageSize() {
        val g = CalibrationGeometry(16, 16, "RGGB", 16)
        val frame = FloatArray(256) { .25f + .01f * g.group(it) }
        frame[5] = 0f
        val flat = master(CalibrationType.FLAT, frame, g)
        val result = flat.imageCopy().pixels
        assertEquals(256, result.size)
        for (index in result.indices) {
            assertTrue("index=$index value=${result[index]}", result[index].isFinite() && result[index] > 0f)
            assertEquals(1.0, result[index].toDouble(), .08)
        }
    }

    @Test fun isolatedPerFrameClippingUsesOnlyItsValidSamplesInTheStreamingMean() {
        val g = CalibrationGeometry(16, 16, "MONO", 16)
        val frames = listOf(
            FloatArray(256) { if (it == 17) 0f else .20f },
            FloatArray(256) { if (it == 31) 1f else .40f }
        )
        val flat = CalibrationBuilder.build(CalibrationType.FLAT, g, capture, 2, { frames[it.toInt()].let {
            LinearImage(g.width, g.height, 1, it.copyOf())
        } })
        val image = flat.imageCopy().pixels
        assertTrue(image[17] != 0f && image[17].isFinite())
        assertTrue(image[31] != 0f && image[31].isFinite())
        assertTrue(image.all { it.isFinite() && it > 0f })
    }

    @Test fun knownMismatchCannotBeOverriddenAndDarkExposureIsNeverScaled() {
        val dark = master(CalibrationType.DARK, FloatArray(8) { .1f })
        val selected = CalibrationSelection(dark = dark)
        for (different in listOf(capture.copy(camera = "other"), capture.copy(gain = 101.0), capture.copy(offset = 13.0),
            capture.copy(roiX = 1), capture.copy(roiY = 1), capture.copy(hardwareBin = 2), capture.copy(readout = "other"),
            capture.copy(exposureUs = 2_000_000.0), capture.copy(temperatureC = -7.99))) {
            rejects("settings_mismatch") { selected.validate(geometry, different) }
        }
        selected.validate(geometry, capture.copy(temperatureC = -8.0))
        selected.validate(geometry, capture.copy(filter = "R")) // Dark is independent of optical filter.
        for (different in listOf(geometry.copy(width = 5), geometry.copy(validBits = 16), geometry.copy(layout = "RGGB"))) {
            rejects("geometry_mismatch") { selected.validate(different, capture) }
        }
        val bias = master(CalibrationType.BIAS, FloatArray(8) { .1f })
        CalibrationSelection(bias = bias).validate(geometry, capture.copy(exposureUs = 2_000_000.0))
        val flat = master(CalibrationType.FLAT, FloatArray(8) { .5f })
        CalibrationSelection(flat = flat).validate(geometry, capture.copy(exposureUs = 2_000_000.0))
        rejects("settings_mismatch") { CalibrationSelection(flat = flat).validate(geometry, capture.copy(filter = "R")) }
    }

    @Test fun missingCaptureInfoNeverBlocksCalibrationOrStacking() {
        val unknown = CalibrationCapture()
        CalibrationSelection().validate(geometry, unknown)
        CalibrationBuilder.build(CalibrationType.BIAS, geometry, unknown, 1, { image(FloatArray(8)) })
        val master = CalibrationBuilder.build(CalibrationType.DARK, geometry, unknown, 1, { image(FloatArray(8) { .1f }) })
        val selected = CalibrationSelection(dark = master)
        selected.validate(geometry, capture)
        assertTrue(selected.unknownWarnings(geometry, capture).contains("DARK:exposureUs"))
        assertTrue(selected.unknownWarnings(geometry, capture).contains("DARK:temperatureC"))
        assertTrue(selected.metadata().getValue("calibration").contains("sensor_before_demosaic"))
    }

    @Test fun missingCaptureFieldsListEveryRequiredMetadataField() {
        assertEquals(
            setOf("camera", "gain", "roiX", "roiY", "hardwareBin", "readout"),
            unknownCaptureFields(CalibrationType.BIAS, CalibrationCapture()).toSet()
        )
        assertTrue(unknownCaptureFields(CalibrationType.DARK, CalibrationCapture())
            .containsAll(setOf("exposureUs", "temperatureC")))
        assertTrue(unknownCaptureFields(CalibrationType.FLAT, CalibrationCapture()).contains("filter"))
    }

    @Test fun streamingMeanReadsInOrderDoesNotMutateInputsAndCancels() {
        val frames = listOf(image(FloatArray(8) { .1f }), image(FloatArray(8) { .2f }), image(FloatArray(8) { .3f }))
        val order = mutableListOf<Long>(); val progress = mutableListOf<Long>()
        val mean = CalibrationBuilder.build(CalibrationType.BIAS, geometry, capture, 3, {
            order += it; frames[it.toInt()]
        }, progress = { done, total -> assertEquals(3L, total); progress += done })
        assertEquals(listOf(0L, 1L, 2L), order); assertEquals(listOf(1L, 2L, 3L), progress)
        assertArrayEquals(FloatArray(8) { .2f }, mean.imageCopy().pixels, 2e-8f)
        assertEquals(0L, mean.heapBytes)
        assertEquals(geometry.sampleCount * 4L, mean.nativeBytes)
        assertEquals(.1f, frames[0].pixels[0], 0f)
        assertEquals(2L * (geometry.sampleCount * 6L) + geometry.sampleCount * 8L + 4L * 1024 * 1024,
            CalibrationBuilder.estimatedBuildBytes(geometry))
        assertEquals(CalibrationBuilder.estimatedBuildBytes(geometry), CalibrationBuilder.estimatedBuildBytes(geometry, true))
        assertEquals(geometry.sampleCount * 3L,
            CalibrationBuilder.estimatedBuildBytes(geometry, withSampleCounts = true) -
                CalibrationBuilder.estimatedBuildBytes(geometry))
        var reads = 0
        try {
            CalibrationBuilder.build(CalibrationType.BIAS, geometry, capture, 100, { reads++; frames[0] },
                cancelCheck = { if (reads >= 2) throw CancellationException("cancel") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(2, reads) }
        rejects("dimensions") { CalibrationGeometry(0, 4096, "MONO", 16) }
    }

    @Test fun sensorRoiCfaGeometryMatchesDecoderAndRejectsUnknownEvenBin() {
        val patterns = listOf(PixelFormat.BAYER_RG12, PixelFormat.BAYER_GR12, PixelFormat.BAYER_GB12, PixelFormat.BAYER_BG12)
        val names = listOf("RGGB", "GRBG", "GBRG", "BGGR")
        for (base in patterns.indices) for (x in 0..1) for (y in 0..1) {
            val traits = PixelTraits(validBits = 12, roiX = x, roiY = y, bayerPatternAtOutput = false)
            assertEquals(names[base xor x xor (y * 2)], CalibrationGeometry.from(4, 4, patterns[base], traits).layout)
        }
        rejects("unknown_cfa") { CalibrationGeometry.from(4, 4, patterns[0], PixelTraits(binning = 2, bayerPatternAtOutput = false)) }
    }

    @Test fun borrowedCalibrationReusesOutputAndRecoversAfterCancellation() {
        val bias = master(CalibrationType.BIAS,FloatArray(8) { .1f })
        val selected = CalibrationSelection(bias=bias)
        val workspace = CalibrationSelection.Workspace()
        val firstInput = image(FloatArray(8) { .2f+it*.01f })
        val expected = selected.apply(firstInput,geometry,capture)
        val first = selected.applyBorrowed(firstInput,geometry,capture,workspace)
        assertArrayEquals(expected.pixels,first.pixels,0f)
        val second = selected.applyBorrowed(image(FloatArray(8) { .5f-it*.01f }),geometry,capture,workspace)
        assertSame(first.pixels,second.pixels)
        assertEquals(1L,workspace.arrayAllocations)
        assertEquals(32L,workspace.retainedBytes)
        assertArrayEquals(FloatArray(8) { .2f+it*.01f },firstInput.pixels,0f)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            selected.applyBorrowed(firstInput,geometry,capture,workspace) {
                if (++checks == 3) throw CancellationException("calibration")
            }
        }
        assertArrayEquals(expected.pixels,selected.applyBorrowed(firstInput,geometry,capture,workspace).pixels,0f)
        val disabled = CalibrationSelection()
        assertSame(firstInput,disabled.applyBorrowed(firstInput,geometry,capture,CalibrationSelection.Workspace()))
        workspace.release()
        assertEquals(0L,workspace.retainedBytes)
    }

    @Test fun directMastersUseRustForFullFrameAndRegionWithKotlinEquivalentOutput() {
        assertTrue("Rust calibration library must load for JVM tests", NativeCalibration.loaded)
        for (layout in listOf("MONO", "RGB")) {
            val g = CalibrationGeometry(14, 14, layout, 16)
            val samples = g.sampleCount
            val biasValues = FloatArray(samples) { .04f + (it % 7) * .003f }
            val flatValues = FloatArray(samples) { .7f + (it % 5) * .13f }
            val light = image(FloatArray(samples) { .08f + it * .001f }, g)
            val directBias = directMaster(CalibrationType.BIAS, biasValues, g)
            val directFlat = directMaster(CalibrationType.FLAT, flatValues, g)
            val heapBias = heapMaster(CalibrationType.BIAS, biasValues, g)
            val heapFlat = heapMaster(CalibrationType.FLAT, flatValues, g)
            assertEquals(0L, directBias.heapBytes)
            assertNotNull(directBias.nativeBuffer())

            val native = CalibrationSelection(bias = directBias, flat = directFlat)
            val oracle = CalibrationSelection(bias = heapBias, flat = heapFlat)
            assertArrayEquals("$layout full", oracle.apply(light, g, capture).pixels,
                native.apply(light, g, capture).pixels, 0f)

            val region = PixelRoi(left = 1, top = 1, width = 12, height = 12)
            val regionLight = crop(light, region)
            assertArrayEquals("$layout region",
                oracle.applyRegion(regionLight, g, capture, region).pixels,
                native.applyRegion(regionLight, g, capture, region).pixels, 0f)

            val workspace = CalibrationSelection.Workspace()
            assertArrayEquals("$layout borrowed region",
                oracle.applyRegionBorrowed(regionLight, g, capture, region, workspace).pixels,
                native.applyRegionBorrowed(regionLight, g, capture, region, workspace).pixels, 0f)
            workspace.release()
        }
    }

    @Test fun rustCalibrationReportsNonfiniteInputAndOutput() {
        val bias = directMaster(CalibrationType.BIAS, FloatArray(8) { .1f })
        val selected = CalibrationSelection(bias = bias)
        val nonfinite = FloatArray(8) { .2f }
        nonfinite[3] = Float.NaN
        rejects("nonfinite_light") { selected.apply(image(nonfinite), geometry, capture) }

        val tinyFlat = directMaster(CalibrationType.FLAT, FloatArray(8) { 1e-5f })
        rejects("nonfinite_output") {
            CalibrationSelection(flat = tinyFlat).apply(image(FloatArray(8) { Float.MAX_VALUE }), geometry, capture)
        }
    }

    @Test fun aliasedBorrowedDestinationUsesSafeKotlinFallbackAndReusesWorkspace() {
        assertTrue("Rust calibration library must load for JVM tests", NativeCalibration.loaded)
        val g = CalibrationGeometry(14, 14, "MONO", 16)
        val bias = master(CalibrationType.BIAS, FloatArray(196) { .05f + (it % 9) * .001f }, g)
        val selected = CalibrationSelection(bias = bias)
        val workspace = CalibrationSelection.Workspace()
        val first = selected.applyBorrowed(
            image(FloatArray(196) { .1f + it * .001f }, g),
            g,
            capture,
            workspace
        )
        val aliasedInput = image(first.pixels, g)
        val expected = selected.apply(aliasedInput, g, capture)
        val actual = selected.applyBorrowed(aliasedInput, g, capture, workspace)
        assertSame(first.pixels, actual.pixels)
        assertArrayEquals(expected.pixels, actual.pixels, 0f)
        workspace.release()
    }
}
