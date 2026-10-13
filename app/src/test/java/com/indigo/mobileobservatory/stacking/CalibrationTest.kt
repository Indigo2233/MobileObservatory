package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.PixelFormat
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class CalibrationTest {
    private val geometry = CalibrationGeometry(4, 2, "MONO", 12)
    private val capture = CalibrationCapture("camera", 1_000_000.0, 100.0, 12.0, 0, 0, 1, "L", "RAW", -10.0)
    private fun image(values: FloatArray, g: CalibrationGeometry = geometry) = LinearImage(g.width, g.height, g.channels, values)
    private fun master(type: CalibrationType, values: FloatArray, g: CalibrationGeometry = geometry,
                       c: CalibrationCapture = capture, bias: CalibrationMaster? = null): CalibrationMaster =
        CalibrationBuilder.build(type, g, c, 1, { image(values.copyOf(), g) }, bias = bias)
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
        for (bad in listOf(0f, 1f)) rejects("clipped_flat") {
            master(CalibrationType.FLAT, FloatArray(8) { if (it == 3) bad else .5f })
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.01f)) rejects("invalid_source") {
            master(CalibrationType.FLAT, FloatArray(8) { if (it == 3) bad else .5f })
        }
        val bias = master(CalibrationType.BIAS, FloatArray(8) { .6f })
        for (response in listOf(.5f, .6f)) rejects("invalid_flat_response") {
            master(CalibrationType.FLAT, FloatArray(8) { response }, bias = bias)
        }
        rejects("uncalibrated sensor means") { master(CalibrationType.DARK, FloatArray(8) { .7f }, bias = bias) }
    }

    @Test fun knownMismatchCannotBeOverriddenAndDarkExposureIsNeverScaled() {
        val dark = master(CalibrationType.DARK, FloatArray(8) { .1f })
        val selected = CalibrationSelection(dark = dark, allowUnknownCapture = true)
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
        rejects("settings_mismatch") { CalibrationSelection(flat = flat, allowUnknownCapture = true).validate(geometry, capture.copy(filter = "R")) }
    }

    @Test fun unknownCaptureRequiresExplicitConfirmationButEmptySelectionAlwaysWorks() {
        val unknown = CalibrationCapture()
        CalibrationSelection().validate(geometry, unknown)
        rejects("unknown_settings") { CalibrationBuilder.build(CalibrationType.BIAS, geometry, unknown, 1, { image(FloatArray(8)) }) }
        val master = CalibrationBuilder.build(CalibrationType.DARK, geometry, unknown, 1, { image(FloatArray(8) { .1f }) }, allowUnknownCapture = true)
        rejects("unknown_settings") { CalibrationSelection(dark = master).validate(geometry, capture) }
        val selected = CalibrationSelection(dark = master, allowUnknownCapture = true)
        selected.validate(geometry, capture)
        assertTrue(selected.unknownWarnings(geometry, capture).contains("DARK:exposureUs"))
        assertTrue(selected.unknownWarnings(geometry, capture).contains("DARK:temperatureC"))
        assertTrue(selected.metadata().getValue("calibration").contains("sensor_before_demosaic"))
    }

    @Test fun streamingMeanReadsInOrderDoesNotMutateInputsAndCancels() {
        val frames = listOf(image(FloatArray(8) { .1f }), image(FloatArray(8) { .2f }), image(FloatArray(8) { .3f }))
        val order = mutableListOf<Long>(); val progress = mutableListOf<Long>()
        val mean = CalibrationBuilder.build(CalibrationType.BIAS, geometry, capture, 3, {
            order += it; frames[it.toInt()]
        }, progress = { done, total -> assertEquals(3L, total); progress += done })
        assertEquals(listOf(0L, 1L, 2L), order); assertEquals(listOf(1L, 2L, 3L), progress)
        assertArrayEquals(FloatArray(8) { .2f }, mean.imageCopy().pixels, 2e-8f)
        assertEquals(.1f, frames[0].pixels[0], 0f)
        assertEquals(CalibrationBuilder.estimatedBuildBytes(geometry) + geometry.sampleCount * 4L, CalibrationBuilder.estimatedBuildBytes(geometry, true))
        var reads = 0
        try {
            CalibrationBuilder.build(CalibrationType.BIAS, geometry, capture, 100, { reads++; frames[0] },
                cancelCheck = { if (reads >= 2) throw CancellationException("cancel") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(2, reads) }
        rejects("dimensions") { CalibrationGeometry(4096, 4096, "MONO", 16) }
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
                if (++checks == 2) throw CancellationException("calibration")
            }
        }
        assertArrayEquals(expected.pixels,selected.applyBorrowed(firstInput,geometry,capture,workspace).pixels,0f)
        val disabled = CalibrationSelection()
        assertSame(firstInput,disabled.applyBorrowed(firstInput,geometry,capture,CalibrationSelection.Workspace()))
        workspace.release()
        assertEquals(0L,workspace.retainedBytes)
    }
}
