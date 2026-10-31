package com.indigo.mobileobservatory.stacking

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

class StackDisplayHighlightsTest {
    private fun galaxy(): LinearImage = LinearImage(256, 128, 3, FloatArray(256 * 128 * 3) { i ->
        val x = i / 3 % 256; val y = i / 3 / 256
        val disk = .008 * exp(-((x - 128.0) / 55).pow(2) - ((y - 64.0) / 13).pow(2))
        val core = .55 * exp(-((x - 128.0).pow(2) + (y - 64.0).pow(2)) / 8)
        (.003 + .00015 * sin(x * .73 + y * .37) + disk + core).toFloat()
    })

    @Test fun autoStretchRetainsUnclippedGalaxyCoreAndVisibleDiskForBothPresets() {
        val image = galaxy(); val original = image.pixels.copyOf()
        for (preset in listOf(ProcessingPreset.NATURAL, ProcessingPreset.INTENSE)) {
            val requested = StackProcessingParameters.preset(preset).copy(
                autoWhiteBalance = false, localContrast = 0.0, background = BackgroundParameters(enabled = false))
            val recipe = StackAutoStretch.estimate(image, requested)!!
            val result = StackPostProcessor.process(image, recipe).image
            fun level(x: Int, y: Int) = result.pixels[(y * image.width + x) * 3]
            val core = level(128, 64); val inner = level(130, 64)
            val sky = level(20, 20); val disk = level(150, 64)
            println("GALAXY $preset sky=$sky disk=$disk core=$core inner=$inner white=${recipe.whitePoint}")
            assertTrue("Unclipped core must retain display headroom: $preset $core", core < .985f)
            assertTrue("Core structure must survive quantized display",
                (core * 255 + .5f).toInt() > (inner * 255 + .5f).toInt())
            assertTrue("Disk remains visible", disk > sky + .1f)
            assertTrue("Background stays moderate", sky in .02f.. .25f)
            assertTrue(result.pixels.all { it.isFinite() && it in 0f..1f })
        }
        assertArrayEquals(original, image.pixels, 0f)
    }

    @Test fun strongerHighlightCompressionLowersBrightValuesWithoutFlatteningTheirOrder() {
        val image = LinearImage(6, 1, 1, floatArrayOf(.001f, .01f, .04f, .12f, .4f, .8f))
        val base = StackProcessingParameters(blackPoint = .0005, whitePoint = .08, midtone = .3,
            curve = 12.0, highlightCompression = .15, background = BackgroundParameters(enabled = false))
        val light = StackPostProcessor.process(image, base).image.pixels
        val strong = StackPostProcessor.process(image, base.copy(highlightCompression = .8)).image.pixels
        assertTrue("Compression should lower highlight brightness", strong[3] < light[3])
        for (i in 1 until strong.size) assertTrue("Retain bright signal ordering at $i", strong[i] > strong[i - 1])
        assertTrue(strong.last() < .985f)
        val prepared = StackPostProcessor.prepare(image, base.copy(highlightCompression = .8))
        val preview = FloatArray(image.pixels.size)
        prepared.writePreviewRow(0, preview)
        assertArrayEquals(strong, preview, 1f / 255)
    }

    @Test fun freshDisplayPresetsBalanceColourWhileRawPreservesSensorValues() {
        val image = LinearImage(64, 48, 3, FloatArray(64 * 48 * 3) { i ->
            ((.003 + (i / 3 % 64) * .00005) * when (i % 3) { 0 -> 1.4; 1 -> 1.0; else -> .7 }).toFloat()
        })
        for (preset in listOf(ProcessingPreset.NATURAL, ProcessingPreset.INTENSE)) {
            val recipe = StackProcessingParameters.preset(preset).copy(localContrast = 0.0,
                background = BackgroundParameters(enabled = false))
            assertTrue("New $preset presets should enable automatic colour balance", recipe.autoWhiteBalance)
            val result = StackPostProcessor.process(image, StackAutoStretch.estimate(image, recipe)!!)
            for (p in 0 until image.pixelCount) {
                assertEquals(result.image.pixels[p * 3], result.image.pixels[p * 3 + 1], 1e-5f)
                assertEquals(result.image.pixels[p * 3 + 1], result.image.pixels[p * 3 + 2], 1e-5f)
            }
        }
        assertSame(image, StackPostProcessor.process(image, StackProcessingParameters.preset(ProcessingPreset.RAW)).image)
    }
}
